/**
 * External upstream messaging and routing.
 *
 * <p>The package has four distinct responsibilities:</p>
 *
 * <pre>
 * NodeUpstream
 *     | UpstreamData
 *     v
 * UpstreamStream
 *     | UpstreamMessage { NodeId, payload }
 *     v
 * UpstreamGateway
 *     |
 *     v
 * UpstreamConnector
 * </pre>
 *
 * <p>{@code UpstreamStream} is the semantic boundary seen by NodeUpstream.
 * {@code UpstreamMessage} adds only the NodeId needed for routing.
 * {@code UpstreamGateway} owns per-node inbound queues and connector routing; its
 * payload is opaque. {@code UpstreamConnector} owns transport/session mechanics.
 * Concrete connectors must not implement RaceData, TimingData, SYNC, ACK, REQ or
 * PING protocol semantics.</p>
 */
package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;
