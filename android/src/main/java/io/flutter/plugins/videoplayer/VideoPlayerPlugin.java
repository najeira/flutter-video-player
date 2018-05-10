// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.LongSparseArray;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoListener;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.TextureRegistry;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VideoPlayerPlugin implements MethodCallHandler {
  private static class VideoPlayer implements Player.EventListener, VideoListener {
    private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();
    private static final CookieManager DEFAULT_COOKIE_MANAGER;

    static {
      DEFAULT_COOKIE_MANAGER = new CookieManager();
      DEFAULT_COOKIE_MANAGER.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    }

    private final TextureRegistry.SurfaceTextureEntry textureEntry;
    private EventChannel.EventSink eventSink;
    private final EventChannel eventChannel;
    private final Activity activity;
    private boolean isInitialized = false;

    private Uri dataSource;
    private DataSource.Factory mediaDataSourceFactory;
    private SimpleExoPlayer player;
    private DefaultTrackSelector trackSelector;

    private int width;
    private int height;
    private TrackGroupArray lastSeenTrackGroupArray;

    /*VideoPlayer(
        Activity activity,
        EventChannel eventChannel,
        TextureRegistry.SurfaceTextureEntry textureEntry,
        AssetFileDescriptor afd,
        final Result result) {
      this.activity = activity;
      this.eventChannel = eventChannel;
      //this.mediaPlayer = new MediaPlayer();
      this.textureEntry = textureEntry;
    }*/

    VideoPlayer(
        Activity activity,
        EventChannel eventChannel,
        TextureRegistry.SurfaceTextureEntry textureEntry,
        String dataSource,
        Result result) {
      this.activity = activity;
      this.eventChannel = eventChannel;
      //this.mediaPlayer = new MediaPlayer();
      this.textureEntry = textureEntry;
      this.dataSource = Uri.parse(dataSource);
      setupVideoPlayer(textureEntry, result);
    }

    private void setupVideoPlayer(TextureRegistry.SurfaceTextureEntry textureEntry, Result result) {
      eventChannel.setStreamHandler(
              new EventChannel.StreamHandler() {
                @Override
                public void onListen(Object o, EventChannel.EventSink sink) {
                  eventSink = sink;
                  sendInitialized();
                }

                @Override
                public void onCancel(Object o) {
                  eventSink = null;
                }
              });

      initializePlayer();

      Map<String, Object> reply = new HashMap<>();
      reply.put("textureId", textureEntry.id());
      result.success(reply);
    }

    private void initializePlayer() {
      if (player == null) {
        String userAgent = Util.getUserAgent(this.activity, "Flutter");
        DataSource.Factory baseDataSourceFactory = new DefaultHttpDataSourceFactory(userAgent, BANDWIDTH_METER);
        mediaDataSourceFactory = new DefaultDataSourceFactory(this.activity, BANDWIDTH_METER, baseDataSourceFactory);

        if (CookieHandler.getDefault() != DEFAULT_COOKIE_MANAGER) {
          CookieHandler.setDefault(DEFAULT_COOKIE_MANAGER);
        }

        TrackSelection.Factory adaptiveTrackSelectionFactory = new AdaptiveTrackSelection.Factory(BANDWIDTH_METER);
        trackSelector = new DefaultTrackSelector(adaptiveTrackSelectionFactory);
        lastSeenTrackGroupArray = null;

        player = ExoPlayerFactory.newSimpleInstance(this.activity, trackSelector);
        player.addListener(this);
        player.addVideoListener(this);
        //player.setPlayWhenReady(shouldAutoPlay);
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
      }

      MediaSource mediaSource = buildMediaSource(dataSource, null);
      player.prepare(mediaSource, false, false);

      player.setVideoSurface(new Surface(textureEntry.surfaceTexture()));
    }

    private MediaSource buildMediaSource(Uri uri, String overrideExtension) {
      int type = TextUtils.isEmpty(overrideExtension) ? Util.inferContentType(uri)
              : Util.inferContentType("." + overrideExtension);
      if (type == C.TYPE_HLS) {
        return new HlsMediaSource.Factory(mediaDataSourceFactory).createMediaSource(uri);
      } else if (type == C.TYPE_OTHER) {
        return new ExtractorMediaSource.Factory(mediaDataSourceFactory).createMediaSource(uri);
      }
      throw new IllegalStateException("Unsupported type: " + type);
    }

    void play() {
      player.setPlayWhenReady(true);
    }

    void pause() {
      player.setPlayWhenReady(false);
    }

    void setLooping(boolean value) {
      player.setRepeatMode(value ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    }

    void setVolume(double value) {
      float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
      player.setVolume(bracketedValue);
    }

    void seekTo(long location) {
      player.seekTo(location);
    }

    long getPosition() {
      return player.getCurrentPosition();
    }

    private void sendInitialized() {
      if (isInitialized && eventSink != null) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "initialized");
        event.put("duration", player.getDuration());
        event.put("width", width);
        event.put("height", height);
        eventSink.success(event);
      }
    }

    void dispose() {
      if (player != null) {
        player.release();
        player = null;
        trackSelector = null;
      }
      textureEntry.release();
      eventChannel.setStreamHandler(null);
    }

    private void bufferingUpdate() {
      if (eventSink != null) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "bufferingUpdate");
        List<Integer> range = Arrays.asList(0, (int) player.getBufferedPosition());
        // iOS supports a list of buffered ranges, so here is a list with a single range.
        event.put("values", Collections.singletonList(range));
        eventSink.success(event);
      }
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {

    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
      if (trackGroups != lastSeenTrackGroupArray) {
        MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
        if (mappedTrackInfo != null) {
          if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_VIDEO)
                  == MappingTrackSelector.MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
            if (eventSink != null) {
              eventSink.error("VideoError", "再生できない形式の映像です", null);
            }
          }
          if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_AUDIO)
                  == MappingTrackSelector.MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
            if (eventSink != null) {
              eventSink.error("VideoError", "再生できない形式の音声です", null);
            }
          }
        }
        lastSeenTrackGroupArray = trackGroups;
      }
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
      bufferingUpdate();
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
      if (playbackState == Player.STATE_READY) {
        if (!isInitialized) {
          isInitialized = true;
          sendInitialized();
        } else {
          bufferingUpdate();
        }
      } else if (playbackState == Player.STATE_ENDED) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "completed");
        eventSink.success(event);
      } else if (playbackState == Player.STATE_BUFFERING) {
        bufferingUpdate();
      }
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {

    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {

    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
      String errorString = null;
      if (error.type == ExoPlaybackException.TYPE_RENDERER) {
        Exception cause = error.getRendererException();
        if (cause instanceof MediaCodecRenderer.DecoderInitializationException) {
          // Special case for decoder initialization failures.
          MediaCodecRenderer.DecoderInitializationException decoderInitializationException =
                  (MediaCodecRenderer.DecoderInitializationException) cause;
          if (decoderInitializationException.decoderName == null) {
            if (decoderInitializationException.getCause() instanceof MediaCodecUtil.DecoderQueryException) {
              errorString = String.format("再生できません");
            } else if (decoderInitializationException.secureDecoderRequired) {
              errorString = String.format("再生できない形式(%s)です", decoderInitializationException.mimeType);
            } else {
              errorString = String.format("再生できない形式(%s)です", decoderInitializationException.mimeType);
            }
          } else {
            errorString = String.format("再生できない形式(%s)です", decoderInitializationException.decoderName);
          }
        }
      }

      if (isBehindLiveWindow(error)) {
        initializePlayer();
      }

      if (eventSink != null) {
        eventSink.error("VideoError", errorString, null);
      }
    }

    @Override
    public void onPositionDiscontinuity(int reason) {

    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {

    }

    @Override
    public void onSeekProcessed() {

    }

    private static boolean isBehindLiveWindow(ExoPlaybackException e) {
      if (e.type != ExoPlaybackException.TYPE_SOURCE) {
        return false;
      }
      Throwable cause = e.getSourceException();
      while (cause != null) {
        if (cause instanceof BehindLiveWindowException) {
          return true;
        }
        cause = cause.getCause();
      }
      return false;
    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
      this.width = width;
      this.height = height;
      sendInitialized();
    }

    @Override
    public void onRenderedFirstFrame() {

    }
  }

  public static void registerWith(Registrar registrar) {
    final MethodChannel channel =
        new MethodChannel(registrar.messenger(), "flutter.io/videoPlayer");
    channel.setMethodCallHandler(new VideoPlayerPlugin(registrar));
  }

  private VideoPlayerPlugin(Registrar registrar) {
    this.registrar = registrar;
    this.videoPlayers = new LongSparseArray<>();
  }

  private final LongSparseArray<VideoPlayer> videoPlayers;
  private final Registrar registrar;

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    TextureRegistry textures = registrar.textures();
    if (textures == null) {
      result.error("no_activity", "video_player plugin requires a foreground activity", null);
      return;
    }
    switch (call.method) {
      case "init":
        int size = videoPlayers.size();
        for (int i = 0; i < size; i++) {
          VideoPlayer player = videoPlayers.valueAt(i);
          player.dispose();
        }
        videoPlayers.clear();
        break;
      case "create":
        {
          TextureRegistry.SurfaceTextureEntry handle = textures.createSurfaceTexture();
          EventChannel eventChannel =
              new EventChannel(
                  registrar.messenger(), "flutter.io/videoPlayer/videoEvents" + handle.id());

          VideoPlayer player;
          if (call.argument("asset") != null) {
            result.error(
                    "IOError",
                    "Error trying to access asset "
                            + (String) call.argument("asset")
                            + ". ",
                    null);
          } else {
            player = new VideoPlayer(registrar.activity(), eventChannel, handle, (String) call.argument("uri"), result);
            videoPlayers.put(handle.id(), player);
          }
          break;
        }
      default:
        {
          long textureId = ((Number) call.argument("textureId")).longValue();
          VideoPlayer player = videoPlayers.get(textureId);
          if (player == null) {
            result.error(
                "Unknown textureId",
                "No video player associated with texture id " + textureId,
                null);
            return;
          }
          onMethodCall(call, result, textureId, player);
          break;
        }
    }
  }

  private void onMethodCall(MethodCall call, Result result, long textureId, VideoPlayer player) {
    switch (call.method) {
      case "setLooping":
        player.setLooping((Boolean) call.argument("looping"));
        result.success(null);
        break;
      case "setVolume":
        player.setVolume((Double) call.argument("volume"));
        result.success(null);
        break;
      case "play":
        player.play();
        result.success(null);
        break;
      case "pause":
        player.pause();
        result.success(null);
        break;
      case "seekTo":
        int location = ((Number) call.argument("location")).intValue();
        player.seekTo(location);
        result.success(null);
        break;
      case "position":
        result.success(player.getPosition());
        break;
      case "dispose":
        player.dispose();
        videoPlayers.remove(textureId);
        result.success(null);
        break;
      default:
        result.notImplemented();
        break;
    }
  }
}
