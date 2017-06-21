package com.linkedin.venice.router.api;

import com.linkedin.ddsstorage.base.misc.Metrics;
import com.linkedin.ddsstorage.router.api.HostFinder;
import com.linkedin.ddsstorage.router.api.HostHealthMonitor;
import com.linkedin.ddsstorage.router.api.PartitionFinder;
import com.linkedin.ddsstorage.router.api.ResourcePath;
import com.linkedin.ddsstorage.router.api.RouterException;
import com.linkedin.ddsstorage.router.api.Scatter;
import com.linkedin.ddsstorage.router.api.ScatterGatherMode;
import com.linkedin.ddsstorage.router.api.ScatterGatherRequest;
import com.linkedin.venice.exceptions.QuotaExceededException;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.router.api.path.VenicePath;
import com.linkedin.venice.router.throttle.ReadRequestThrottler;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

import static io.netty.handler.codec.http.HttpResponseStatus.*;

public class VeniceDelegateMode extends ScatterGatherMode {

  /**
   * This mode will initiate a request per partition, which is suitable for single-get, and it could be the default mode
   * for all other requests.
   */
  private static final ScatterGatherMode SCATTER_GATHER_MODE_FOR_SINGLE_GET = ScatterGatherMode.GROUP_BY_PARTITION;

  /**
   * This mode will do the aggregation per host first, and then initiate a request per host.
   *
   * TODO: we could develop more scatter modes for multi-get request in the future and make it configurable.
   */
  private static final ScatterGatherMode SCATTER_GATHER_MODE_FOR_MULTI_GET = ScatterGatherMode.GROUP_BY_GREEDY_HOST;

  private ReadRequestThrottler readRequestThrottler;

  public VeniceDelegateMode() {
    super("VENICE_DELEGATE_MODE", false);
  }

  public void initReadRequestThrottler(ReadRequestThrottler requestThrottler) {
    if (null != this.readRequestThrottler) {
      throw RouterExceptionAndTrackingUtils.newVeniceExceptionAndTracking(Optional.empty(), Optional.empty(), INTERNAL_SERVER_ERROR,
          "ReadRequestThrottle has already been initialized before, and no further update expected!");
    }
    this.readRequestThrottler = requestThrottler;
  }

  @Nonnull
  @Override
  public <H, P extends ResourcePath<K>, K, R> Scatter<H, P, K> scatter(@Nonnull Scatter<H, P, K> scatter,
      @Nonnull String requestMethod, @Nonnull String resourceName, @Nonnull PartitionFinder<K> partitionFinder,
      @Nonnull HostFinder<H, R> hostFinder, @Nonnull HostHealthMonitor<H> hostHealthMonitor, @Nonnull R roles,
      Metrics metrics) throws RouterException {
    if (null == readRequestThrottler) {
      throw RouterExceptionAndTrackingUtils.newRouterExceptionAndTracking(Optional.empty(), Optional.empty(), INTERNAL_SERVER_ERROR,
          "Read request throttle has not been setup yet");
    }
    P path = scatter.getPath();
    if (! (path instanceof VenicePath)) {
      throw RouterExceptionAndTrackingUtils.newRouterExceptionAndTracking(Optional.empty(), Optional.empty(),INTERNAL_SERVER_ERROR,
          "VenicePath is expected, but received " + path.getClass());
    }
    VenicePath venicePath = (VenicePath)path;
    String storeName = venicePath.getStoreName();
    ScatterGatherMode scatterMode = null;
    switch (venicePath.getRequestType()) {
      case MULTI_GET:
        scatterMode = SCATTER_GATHER_MODE_FOR_MULTI_GET;
        break;
      case SINGLE_GET:
        scatterMode = SCATTER_GATHER_MODE_FOR_SINGLE_GET;
        break;
      default:
        throw RouterExceptionAndTrackingUtils.newRouterExceptionAndTracking(Optional.of(storeName), Optional.of(venicePath.getRequestType()),
            INTERNAL_SERVER_ERROR, "Unknown request type: " + venicePath.getRequestType());
    }

    Scatter finalScatter = scatterMode.scatter(scatter, requestMethod, resourceName, partitionFinder, hostFinder,
        hostHealthMonitor, roles, metrics);

    for (ScatterGatherRequest<H, K> part : scatter.getOnlineRequests()) {
      int hostCount = part.getHosts().size();
      if (0 == hostCount) {
        throw RouterExceptionAndTrackingUtils.newRouterExceptionAndTracking(Optional.of(storeName), Optional.of(venicePath.getRequestType()),
            INTERNAL_SERVER_ERROR, "Could not find ready-to-serve replica for request: " + part);
      }
      H host = part.getHosts().get(0);
      if (hostCount > 1) {
        List<H> hosts = part.getHosts();
        host = hosts.get((int) (System.currentTimeMillis() % hostCount));  //cheap random host selection
        // Update host selection
        // The downstream (VeniceDispatcher) will only expect one host for a given scatter request.
        H finalHost = host;
        hosts.removeIf(aHost -> !aHost.equals(finalHost));
      }
      if (! (host instanceof Instance)) {
        throw RouterExceptionAndTrackingUtils.newRouterExceptionAndTracking(Optional.of(storeName), Optional.of(venicePath.getRequestType()),
            INTERNAL_SERVER_ERROR, "Ready-to-serve host must be an 'Instance'");
      }
      Instance veniceInstance = (Instance)host;
      int keyCount = path.getPartitionKeys().size();
      try {
        readRequestThrottler.mayThrottleRead(storeName, keyCount * readRequestThrottler.getReadCapacity(),
            veniceInstance.getNodeId());
      } catch (QuotaExceededException e) {
        throw RouterExceptionAndTrackingUtils.newRouterExceptionAndTracking(Optional.of(storeName), Optional.of(venicePath.getRequestType()),
            TOO_MANY_REQUESTS, "Quota exceeds!");
      }
    }

    return finalScatter;
  }
}
