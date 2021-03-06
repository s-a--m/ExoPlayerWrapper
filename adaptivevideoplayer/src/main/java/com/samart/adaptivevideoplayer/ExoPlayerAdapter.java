package com.samart.adaptivevideoplayer;


import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.drm.UnsupportedDrmException;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.mp4.Mp4Extractor;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveVideoTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.samart.adaptivevideoplayer.listeners.PlayerAdaptiveMediaSourceEventListener;
import com.samart.adaptivevideoplayer.listeners.PlayerAudioRendererEventListener;
import com.samart.adaptivevideoplayer.listeners.PlayerDefaultFormatEventListener;
import com.samart.adaptivevideoplayer.listeners.PlayerDrmSessionEventListener;
import com.samart.adaptivevideoplayer.listeners.PlayerEventListener;
import com.samart.adaptivevideoplayer.listeners.PlayerVideoRendererEventListener;
import com.samart.adaptivevideoplayer.model.ContentData;
import com.samart.adaptivevideoplayer.model.Format;

import java.io.File;
import java.util.regex.Pattern;

public class ExoPlayerAdapter {
	
	private static final int DEFAULT_MIN_LOADABLE_RETRY_COUNT = 1;
	private static final int DEFAULT_LIVE_PRESENTATION_DELAY_MS = 1000;
	private static final String DEFAULT_PLAYER_CACHE_SUBDIR_NAME = "adaptiveplayercache";
	private static final long DEFAULT_CACHE_SIZE_MAX_BYTES = 200 * 1024 * 1024;
	public static final long CACHE_FILE_SIZE_MAX_BYTES = DEFAULT_CACHE_SIZE_MAX_BYTES;
	private static final String DEFAULT_USER_AGENT = "Android-Adaptiveplayer";
	private static final int MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 30;
	private static final String TAG = ExoPlayerAdapter.class.getSimpleName();
	private static final long BUFFERING_WATCH_DELAY = 500; // half of a second
	private static final String DRM_MESSAGE_BUILD_DRM_SESSION_FAILED = "build drm session failed";
	private static final String DRM_MESSAGE_SESSION_MANAGER_ERROR = "session manager error";
	private static final String USER_AGENT_MOVIE_ANDROID = "MovieAndroid";
	private static final byte[] ZERO_BYTES_ARRAY = new byte[0];
	private static final int CACHE_SIZE_MAX_BYTES = 2000 * 1024 * 1024;
	private static final Pattern PATTERN_VIDEO = Pattern.compile(".*?video_(\\d).*?");
	private static final Pattern PATTERN_AUDIO = Pattern.compile(".*?audio_(\\d).*?");
	private static final String PLAYER_CACHE_SUBDIR_NAME = "/player";
	private static final int MIN_BUFFER_MS = 10 * 1000;
	/**
	 * Upper bound for video preload time. This value is independent from max memory used
	 * by ExoPlayer's DefaultLoadControl.
	 * {@link DefaultLoadControl#shouldContinueLoading(long)}
	 */
	private static final int MAX_BUFFER_MS = 180 * 60 * 1000; // 3h video
	private static final int BUFFER_FOR_PLAYBACK_MS = 1000;
	private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3000;
	private static final char VIDEO_CHUNK_PREFIX_CHAR = 'v';
	private static final char AUDIO_CHUNK_PREFIX_CHAR = 'a';
	
	private static DefaultBandwidthMeter sBandwidthMeter = null;
	private static SimpleCache sFileCache = null;
	private final Context mContext;
	private final HandlerThread mHandlerThread = new HandlerThread("exoplayeradapter");
	private String mUserAgent = DEFAULT_USER_AGENT;
	private Handler mMessageHandler;
	
	//settings
	private int mMinLoadableRetryCount = DEFAULT_MIN_LOADABLE_RETRY_COUNT;
	private long mLivePresentationDelayMs = DEFAULT_LIVE_PRESENTATION_DELAY_MS;
	private AdaptiveMediaSourceEventListener mAdaptiveFormatListener;
	private ExtractorMediaSource.EventListener mDefaultFormatListener;
	private DefaultDrmSessionManager.EventListener mDrmSessionListener;
	private DashWidevineDrmCallback.DrmRequestProvider mDrmRequestProvider;
	private DrmSessionManager<FrameworkMediaCrypto> mDrmSessionManager;
	private ExoPlayer mPlayer;
	private MediaCodecVideoRenderer mVideoRenderer;
	private VideoRendererEventListener mVideoRendererListener;
	private MediaCodecAudioRenderer mAudioRenderer;
	private com.google.android.exoplayer2.audio.AudioRendererEventListener mAudioRendererListener;
	private ExoPlayer.EventListener mPlayerListener;
	private Surface mSurface;
	private DataSource.Factory mVideoSourceListener;
	private ContentData mContent;
	private final DataSource.Factory mDataSourceFactory;
	
	
	public ExoPlayerAdapter(final Context context) {
		mContext = context;
		
		mHandlerThread.start();
		mMessageHandler = new Handler(mHandlerThread.getLooper());
		
		if (sFileCache == null) {
			initFileCache(DEFAULT_CACHE_SIZE_MAX_BYTES, DEFAULT_PLAYER_CACHE_SUBDIR_NAME, context);
		}
		
		if (sBandwidthMeter == null) {
			sBandwidthMeter = new DefaultBandwidthMeter();
		}
		mDataSourceFactory = new DefaultDataSourceFactory(
			mContext, Util.getUserAgent(mContext, mUserAgent), sBandwidthMeter
		);
	}
	
	public void setFileCacheSize(final int cacheSize) {
		initFileCache(cacheSize, DEFAULT_PLAYER_CACHE_SUBDIR_NAME, mContext);
	}
	
	private void createListeners() {
		mDrmSessionListener = new PlayerDrmSessionEventListener(mMessageHandler);
		mDefaultFormatListener = new PlayerDefaultFormatEventListener(mMessageHandler);
		mAdaptiveFormatListener = new PlayerAdaptiveMediaSourceEventListener(mMessageHandler);
		
		mVideoRendererListener = new PlayerVideoRendererEventListener(mMessageHandler);
		
		mAudioRendererListener = new PlayerAudioRendererEventListener(mMessageHandler);
		
		mPlayerListener = new PlayerEventListener(mMessageHandler);
	}
	
	public void init() {
		try {
			mDrmSessionManager = DefaultDrmSessionManager.<FrameworkMediaCrypto>newWidevineInstance(
				new DashWidevineDrmCallback(mDrmRequestProvider),
				null,
				mMessageHandler, mDrmSessionListener);
		} catch (final UnsupportedDrmException e) {
			mDrmSessionManager = null;
		}
		
		mVideoRenderer =
			new MediaCodecVideoRenderer(mContext, MediaCodecSelector.DEFAULT,
				ExoPlayerFactory.DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS, mDrmSessionManager, false,
				mMessageHandler, mVideoRendererListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
		
		mAudioRenderer =
			new MediaCodecAudioRenderer(MediaCodecSelector.DEFAULT, mDrmSessionManager, true,
				mMessageHandler, mAudioRendererListener, AudioCapabilities.getCapabilities(mContext));
		
		final TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveVideoTrackSelection.Factory(sBandwidthMeter);
		
		final LoadControl loadControl = new DefaultLoadControl(
			new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
			MIN_BUFFER_MS,
			MAX_BUFFER_MS,
			BUFFER_FOR_PLAYBACK_MS,
			BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
		);
		
		mPlayer = ExoPlayerFactory.newInstance(
			new Renderer[] { mVideoRenderer, mAudioRenderer },
			new DefaultTrackSelector(videoTrackSelectionFactory),
			loadControl
		);
		
		mPlayer.addListener(mPlayerListener);
		
		setSurface(mSurface);
	}
	
	public void setSurface(final Surface surface) {
		mSurface = surface;
		
		if (mPlayer != null && mVideoRenderer != null) {
			mPlayer.sendMessages(
				new ExoPlayer.ExoPlayerMessage(mVideoRenderer, C.MSG_SET_SURFACE, surface)
			);
		}
	}
	
	public void prepare(final ContentData content) {
		mContent = content;
		
		final String keyPrefix = createContentCacheKeyPrefix();
		
		final MediaSource mediaSource = buildMediaSource(
			mContent.format,
			Uri.parse(mContent.url),
			new CustomCacheKeySourceFactory(sFileCache, mDataSourceFactory, mContent.format, keyPrefix),
			keyPrefix
		);
		
		mPlayer.prepare(mediaSource);
	}
	
	private String createContentCacheKeyPrefix() {
		
		return mContent.format + mContent.id;
	}
	
	private MediaSource buildMediaSource(final Format contentFormat, final Uri uri, final DataSource.Factory mediaDataSourceFactory, final String cacheKeyPrefix) {
		
		createListeners();
		
		switch (contentFormat) {
			case DASH: {
				return new DashMediaSource(
					uri, new DefaultHttpDataSourceFactory(mUserAgent),
					//custom id for cache keys
					new DashManifestParser(cacheKeyPrefix),
					new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
					mMinLoadableRetryCount, mLivePresentationDelayMs,
					mMessageHandler, mAdaptiveFormatListener);
			}
			case HLS: {
				return new HlsMediaSource(uri, mediaDataSourceFactory, mMessageHandler, mAdaptiveFormatListener);
			}
			case MP4: {
				return new ExtractorMediaSource(uri, mediaDataSourceFactory, Mp4Extractor.FACTORY, mMessageHandler, mDefaultFormatListener);
			}
			case UNDEFINED:
			default: {
				return new ExtractorMediaSource(uri, mediaDataSourceFactory, new DefaultExtractorsFactory(), mMessageHandler, mDefaultFormatListener);
			}
		}
	}
	
	
	private static void initFileCache(final long defaultCacheSizeMaxBytes, final String defaultPlayerCacheSubdirName, final Context context) {
		sFileCache = new SimpleCache(new File(context.getCacheDir(), defaultPlayerCacheSubdirName), new LeastRecentlyUsedCacheEvictor(defaultCacheSizeMaxBytes));
	}
}
