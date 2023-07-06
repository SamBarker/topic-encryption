package io.strimzi.kafka.topicenc.kroxylicious;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.message.FetchRequestData;
import org.apache.kafka.common.message.FetchResponseData;
import org.apache.kafka.common.message.MetadataRequestData;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.MetadataResponseDataJsonConverter;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;

import io.strimzi.kafka.topicenc.EncryptionModule;
import io.strimzi.kafka.topicenc.common.Strings;

import io.kroxylicious.proxy.filter.FetchRequestFilter;
import io.kroxylicious.proxy.filter.FetchResponseFilter;
import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.MetadataResponseFilter;

import static io.strimzi.kafka.topicenc.common.Strings.isNullOrEmpty;
import static java.util.stream.Collectors.toSet;

public class FetchDecryptFilter implements FetchRequestFilter, FetchResponseFilter, MetadataResponseFilter {

    private static final Logger log = LoggerFactory.getLogger(FetchDecryptFilter.class);
    public static final short METADATA_VERSION_SUPPORTING_TOPIC_IDS = (short) 12;

    private final EncryptionModule module;
    private final AsyncLoadingCache<Uuid, String> topicUuidToNameCache;

    public FetchDecryptFilter(TopicEncryptionConfig config) {
        module = new EncryptionModule(config.getPolicyRepository());
        topicUuidToNameCache = config.getTopicUuidToNameCache();
    }

    @Override
    public void onFetchRequest(short apiVersion, RequestHeaderData header, FetchRequestData request, KrpcFilterContext context) {
        boolean allTopicsResolvableToName = request.topics().stream().allMatch(this::isResolvable);
        if (!allTopicsResolvableToName) {
            Set<Uuid> topicIdsToResolve = request.topics().stream().filter(Predicate.not(this::isResolvable)).map(FetchRequestData.FetchTopic::topicId).collect(toSet());
            // send a background metadata request to resolve topic ids, preparing for fetch response
            resolveAndCache(context, topicIdsToResolve);
        }
        context.forwardRequest(header, request);
    }

    @Override
    public void onFetchResponse(short apiVersion, ResponseHeaderData header, FetchResponseData response, KrpcFilterContext context) {
        var unresolvedTopicIds = getUnresolvedTopicIds(response);
        if (unresolvedTopicIds.isEmpty()) {
            decryptFetchResponse(header, response, context);
        }
        else {
            log.warn("We did not know all topic names for {} topic ids within a fetch response, requesting metadata and returning error response",
                    unresolvedTopicIds.size());
            log.debug("We did not know all topic names for topic ids {} within a fetch response, requesting metadata and returning error response", unresolvedTopicIds);
            // we return an error rather than delaying the response to prevent out-of-order responses to the Consumer client.
            // The Filter API only supports synchronous work currently.
            resolveTopicsAndReturnError(header, context, unresolvedTopicIds);
        }
    }

    private Set<Uuid> getUnresolvedTopicIds(FetchResponseData response) {
        return response.responses().stream()
                .filter(Predicate.not(this::isResolvable))
                .map(FetchResponseData.FetchableTopicResponse::topicId)
                .collect(toSet());
    }

    /**
     * We should know the topic names by the time we get the response, because the fetch request sends a metadata request
     * for unknown topic ids before sending the fetch request. This is a safeguard in case that request fails somehow.
     */
    private void resolveTopicsAndReturnError(ResponseHeaderData header, KrpcFilterContext context, Set<Uuid> topicIdsToResolve) {
        // send a background metadata request to resolve topic ids, preparing for future fetches
        resolveAndCache(context, topicIdsToResolve);
        FetchResponseData data = new FetchResponseData();
        data.setErrorCode(Errors.UNKNOWN_SERVER_ERROR.code());
        context.forwardResponse(header, data);
    }

    private void resolveAndCache(KrpcFilterContext context, Set<Uuid> topicIdsToResolve) {
        MetadataRequestData request = new MetadataRequestData();
        topicIdsToResolve.forEach(uuid -> {
            MetadataRequestData.MetadataRequestTopic e = new MetadataRequestData.MetadataRequestTopic();
            e.setTopicId(uuid);
            request.topics().add(e);
        });
        // if the client is sending topic ids we will assume the broker can support at least the lowest metadata apiVersion
        // supporting topicIds
        CompletionStage<MetadataResponseData> stage = context.sendRequest(METADATA_VERSION_SUPPORTING_TOPIC_IDS, request);
        stage.thenAccept(response -> cacheTopicIdToName(response, METADATA_VERSION_SUPPORTING_TOPIC_IDS));
    }

    private void decryptFetchResponse(ResponseHeaderData header, FetchResponseData response, KrpcFilterContext context) {
        for (FetchResponseData.FetchableTopicResponse fetchResponse : response.responses()) {
            Uuid originalUuid = fetchResponse.topicId();
            String originalName = fetchResponse.topic();
            if (isNullOrEmpty(originalName)) {
                fetchResponse.setTopic(getTopicNameForUuid(originalUuid));
                fetchResponse.setTopicId(null);
            }
            try {
                module.decrypt(fetchResponse);
            }
            catch (Exception e) {
                log.error("Failed to decrypt a fetchResponse for topic: " + fetchResponse.topic(), e);
                throw new RuntimeException(e);
            }
            fetchResponse.setTopic(originalName);
            fetchResponse.setTopicId(originalUuid);
        }
        context.forwardResponse(header, response);
    }

    private String getTopicNameForUuid(Uuid originalUuid) {
        //TODO revisit error handling
        try {
            final CompletableFuture<String> topicNameFuture = topicUuidToNameCache.getIfPresent(originalUuid);
            return topicNameFuture != null ? topicNameFuture.get(5, TimeUnit.SECONDS) : null;
        }
        catch (InterruptedException e) {
            log.warn("Caught thread interrupt", e);
            Thread.currentThread().interrupt();
        }
        catch (ExecutionException | TimeoutException e) {
            log.warn("Failed to get ", e);
        }
        return null;
    }

    @Override
    public void onMetadataResponse(short apiVersion, ResponseHeaderData header, MetadataResponseData response, KrpcFilterContext context) {
        cacheTopicIdToName(response, apiVersion);
        context.forwardResponse(header, response);
    }

    private boolean isResolvable(FetchResponseData.FetchableTopicResponse fetchableTopicResponse) {
        return hasTopicName(fetchableTopicResponse.topicId(), fetchableTopicResponse.topic());
    }

    private boolean isResolvable(FetchRequestData.FetchTopic fetchTopic) {
        return hasTopicName(fetchTopic.topicId(), fetchTopic.topic());
    }

    private boolean hasTopicName(Uuid topicId, String topicName) {
        if (!isNullOrEmpty(topicName)) {
            final CompletableFuture<String> futureTopicName = topicUuidToNameCache.getIfPresent(topicId);
            return futureTopicName != null && futureTopicName.isDone() && !Strings.isNullOrEmpty(futureTopicName.getNow(null));
        }
        return false;
    }

    private void cacheTopicIdToName(MetadataResponseData response, short apiVersion) {
        if (log.isTraceEnabled()) {
            log.trace("received metadata response: {}", MetadataResponseDataJsonConverter.write(response, apiVersion));
        }
        response.topics().forEach(topic -> {
            if (topic.errorCode() == 0) {
                if (topic.topicId() != null && !isNullOrEmpty(topic.name())) {
                    topicUuidToNameCache.put(topic.topicId(), CompletableFuture.completedFuture(topic.name()));
                }
                else {
                    log.info("not caching uuid to name because a component was null or empty, topic id {}, topic name {}", topic.topicId(), topic.name());
                }
            }
            else {
                log.warn("error {} on metadata request for topic id {}, topic name {}", Errors.forCode(topic.errorCode()), topic.topicId(), topic.name());
            }
        });
    }
}