// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/// An example of using the plugin, controlling lifecycle and playback of the
/// video.

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

/// Controls play and pause of [controller].
///
/// Toggles play/pause on tap (accompanied by a fading status icon).
///
/// Plays (looping) on initialization, and mutes on deactivation.
class VideoPlayPause extends StatefulWidget {
  final VideoPlayerController controller;

  VideoPlayPause(this.controller);

  @override
  State createState() {
    return new _VideoPlayPauseState();
  }
}

class _VideoPlayPauseState extends State<VideoPlayPause> {
  FadeAnimation imageFadeAnim =
      new FadeAnimation(child: const Icon(Icons.play_arrow, size: 100.0));
  VoidCallback listener;

  _VideoPlayPauseState() {
    listener = () {
      setState(() {});
    };
  }

  VideoPlayerController get controller => widget.controller;

  @override
  void initState() {
    debugPrint("_VideoPlayPauseState.initState");
    super.initState();
    controller.addListener(listener);
    controller.setVolume(1.0);
    controller.play();
  }
  
  @override
  void didUpdateWidget(VideoPlayPause oldWidget) {
    debugPrint("_VideoPlayPauseState.didUpdateWidget");
    super.didUpdateWidget(oldWidget);
    if (widget.controller != oldWidget.controller) {
      oldWidget.controller?.removeListener(listener);
      widget.controller.addListener(listener);
    }
  }

  @override
  void deactivate() {
    debugPrint("_VideoPlayPauseState.deactivate");
    controller.setVolume(0.0);
    controller.removeListener(listener);
    super.deactivate();
  }

  @override
  Widget build(BuildContext context) {
    //debugPrint("_VideoPlayPauseState.build");
    final List<Widget> children = <Widget>[
      new GestureDetector(
        child: new VideoPlayer(controller),
        onTap: () {
          if (!controller.value.initialized) {
            return;
          }
          if (controller.value.isPlaying) {
            imageFadeAnim =
                new FadeAnimation(child: const Icon(Icons.pause, size: 100.0));
            controller.pause();
          } else {
            imageFadeAnim = new FadeAnimation(
                child: const Icon(Icons.play_arrow, size: 100.0));
            controller.play();
          }
        },
      ),
      new Align(
        alignment: Alignment.bottomCenter,
        child: new VideoProgressIndicator(
          controller,
          allowScrubbing: true,
        ),
      ),
      new Center(child: imageFadeAnim),
    ];

    return new Stack(
      fit: StackFit.passthrough,
      children: children,
    );
  }
}

class FadeAnimation extends StatefulWidget {
  final Widget child;
  final Duration duration;

  FadeAnimation({this.child, this.duration: const Duration(milliseconds: 500)});

  @override
  _FadeAnimationState createState() => new _FadeAnimationState();
}

class _FadeAnimationState extends State<FadeAnimation>
    with SingleTickerProviderStateMixin {
  AnimationController animationController;

  @override
  void initState() {
    super.initState();
    animationController =
        new AnimationController(duration: widget.duration, vsync: this);
    animationController.addListener(() {
      if (mounted) {
        setState(() {});
      }
    });
    animationController.forward(from: 0.0);
  }

  @override
  void deactivate() {
    animationController.stop();
    super.deactivate();
  }

  @override
  void didUpdateWidget(FadeAnimation oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.child != widget.child) {
      animationController.forward(from: 0.0);
    }
  }

  @override
  void dispose() {
    animationController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return animationController.isAnimating
        ? new Opacity(
            opacity: 1.0 - animationController.value,
            child: widget.child,
          )
        : new Container();
  }
}

typedef Widget VideoWidgetBuilder(
    BuildContext context, VideoPlayerController controller);

abstract class PlayerLifeCycle extends StatefulWidget {
  final VideoWidgetBuilder childBuilder;
  final String dataSource;

  PlayerLifeCycle(this.dataSource, this.childBuilder);
}

/// A widget connecting its life cycle to a [VideoPlayerController] using
/// a data source from the network.
class NetworkPlayerLifeCycle extends PlayerLifeCycle {
  NetworkPlayerLifeCycle(String dataSource, VideoWidgetBuilder childBuilder)
      : super(dataSource, childBuilder);

  @override
  _NetworkPlayerLifeCycleState createState() =>
      new _NetworkPlayerLifeCycleState();
}

/// A widget connecting its life cycle to a [VideoPlayerController] using
/// an asset as data source
class AssetPlayerLifeCycle extends PlayerLifeCycle {
  AssetPlayerLifeCycle(String dataSource, VideoWidgetBuilder childBuilder)
      : super(dataSource, childBuilder);

  @override
  _AssetPlayerLifeCycleState createState() => new _AssetPlayerLifeCycleState();
}

abstract class _PlayerLifeCycleState extends State<PlayerLifeCycle> {
  VideoPlayerController controller;

  @override
  void initState() {
    debugPrint("_PlayerLifeCycleState.initState");
    super.initState();
    initVideoPlayerController();
  }
  
  /// Subclasses should implement [createVideoPlayerController], which is used
  /// by this method.
  void initVideoPlayerController() {
    controller?.dispose();
    controller = createVideoPlayerController();
    controller.addListener(() {
      debugPrint("initialized=${controller.value.initialized}, "
        "size=${controller.value.size}, "
        "duration=${controller.value.duration}, "
        "position=${controller.value.position}, "
        "error=${controller.value.errorDescription}");
    });
    controller.setLooping(true);
    controller.play();
    controller.initialize().then((_) {
      setState(() {});
    });
  }
  
  @override
  void didUpdateWidget(PlayerLifeCycle oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.dataSource != oldWidget.dataSource) {
      debugPrint("_PlayerLifeCycleState.didUpdateWidget");
      initVideoPlayerController();
    }
  }

  @override
  void dispose() {
    debugPrint("_PlayerLifeCycleState.dispose");
    controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return widget.childBuilder(context, controller);
  }

  VideoPlayerController createVideoPlayerController();
}

class _NetworkPlayerLifeCycleState extends _PlayerLifeCycleState {
  @override
  VideoPlayerController createVideoPlayerController() {
    return new VideoPlayerController.network(widget.dataSource);
  }
}

class _AssetPlayerLifeCycleState extends _PlayerLifeCycleState {
  @override
  VideoPlayerController createVideoPlayerController() {
    return new VideoPlayerController.asset(widget.dataSource);
  }
}

class AspectRatioVideo extends StatefulWidget {
  final VideoPlayerController controller;

  AspectRatioVideo(this.controller);

  @override
  AspectRatioVideoState createState() => new AspectRatioVideoState();
}

class AspectRatioVideoState extends State<AspectRatioVideo> {
  VideoPlayerController get controller => widget.controller;
  
  bool initialized = false;

  @override
  void initState() {
    debugPrint("AspectRatioVideoState.initState");
    super.initState();
    initialized = controller.value.initialized;
    controller.addListener(_controllerChanged);
  }

  @override
  void deactivate() {
    debugPrint("AspectRatioVideoState.deactivate");
    controller.removeListener(_controllerChanged);
    super.deactivate();
  }
  
  void _controllerChanged() {
    if (!mounted) {
      return;
    }
    if (initialized != controller.value.initialized) {
      debugPrint("AspectRatioVideoState._controllerChanged ${initialized} ${controller.value.initialized}");
      setState(() {
        initialized = controller.value.initialized;
      });
    }
  }
  
  @override
  void didUpdateWidget(AspectRatioVideo oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.controller != oldWidget.controller) {
      debugPrint("AspectRatioVideoState.didUpdateWidget");
      oldWidget.controller?.removeListener(_controllerChanged);
      widget.controller.addListener(_controllerChanged);
      setState(() {
        initialized = widget.controller.value.initialized;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    debugPrint("AspectRatioVideoState.build");
    if (initialized) {
      final Size size = controller.value.size;
      return new Center(
        child: new AspectRatio(
          aspectRatio: size.width / size.height,
          child: new VideoPlayPause(controller),
        ),
      );
    } else {
      return new Container(color: Colors.black);
    }
  }
}

class VideoPlayerApp extends StatefulWidget {
  @override
  State<StatefulWidget> createState() {
    return new VideoPlayerAppState();
  }
}

class VideoPlayerAppState extends State<VideoPlayerApp> {
  int selected = 0;
  String get title => videos[selected][0];
  String get dataSource => videos[selected][1];
  
  static List<List<String>> videos = <List<String>>[
    <String>[
      'Big Buck Bunny - adaptive qualities',
      'https://video-dev.github.io/streams/x36xhzz/x36xhzz.m3u8',
    ],
    <String>[
      'Big Buck Bunny - 480p only',
      'https://video-dev.github.io/streams/x36xhzz/url_6/193039199_mp4_h264_aac_hq_7.m3u8',
    ],
    <String>[
      'HLS fMP4 by Bitmovin',
      'https://bitdash-a.akamaihd.net/content/MI201109210084_1/m3u8s-fmp4/f08e80da-bf1d-4e3d-8899-f0f6155f6efa.m3u8',
    ],
    <String>[
      'DK Turntable, PTS shifted by 2.3s',
      'https://video-dev.github.io/streams/pts_shift/master.m3u8',
    ],
  ];
  
  void setVideo(int index) {
    setState(() {
      selected = index;
    });
  }
  
  @override
  Widget build(BuildContext context) {
    debugPrint("VideoPlayerAppState.build ${title} ${dataSource}");
    return new Scaffold(
      appBar: new AppBar(
        title: new Text(title),
        actions: <Widget>[
          new PopupMenuButton<int>(
            onSelected: (int value) {
              setVideo(value);
            },
            itemBuilder: (BuildContext context) {
              int index = 0;
              return videos.map<PopupMenuEntry<int>>((video) {
                index += 1;
                return new PopupMenuItem<int>(
                  value: index - 1,
                  child: new Text(video[0]),
                );
              }).toList();
            },
          ),
        ],
      ),
      body: new Center(
        child: new NetworkPlayerLifeCycle(
          dataSource,
          (BuildContext context, VideoPlayerController controller) =>
              new AspectRatioVideo(controller),
        ),
      ),
    );
  }
}

void main() {
  runApp(new MaterialApp(
    home: new VideoPlayerApp(),
    debugShowCheckedModeBanner: false,
  ));
}
