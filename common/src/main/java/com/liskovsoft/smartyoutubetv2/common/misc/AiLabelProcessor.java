package com.liskovsoft.smartyoutubetv2.common.misc;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.disposables.Disposable;

/**
 * YouTube shows the "Made with AI" label only on the watch page.<br/>
 * Videos that are already known to be labeled are removed when the group is created.
 * The rest are checked in the background and removed once confirmed.
 */
public class AiLabelProcessor implements BrowseProcessor {
    private static final String TAG = AiLabelProcessor.class.getSimpleName();
    private final OnItemRemove mOnItemRemove;
    private final MediaItemService mItemService;
    private final List<Disposable> mActions = new ArrayList<>();

    public AiLabelProcessor(OnItemRemove onItemRemove) {
        mOnItemRemove = onItemRemove;
        mItemService = YouTubeServiceManager.instance().getMediaItemService();
    }

    @Override
    public void process(VideoGroup videoGroup) {
        if (videoGroup == null || videoGroup.isEmpty() || !MediaServiceData.instance().isAiLabeledHidden(videoGroup.getType())) {
            return;
        }

        List<String> videoIds = getVideoIds(videoGroup);

        if (videoIds.isEmpty()) {
            return;
        }

        // Multiple rows are checked at the same time. Forget about the finished ones.
        Helpers.removeIf(mActions, action -> !RxHelper.isAnyActionRunning(action));

        mActions.add(mItemService.getAiLabeledObserve(videoIds)
                .subscribe(videoId -> {
                    Video video = videoGroup.findVideoById(videoId);
                    if (video != null) {
                        mOnItemRemove.onItemRemove(video);
                    }
                },
                error -> {
                    Log.d(TAG, "Cannot check the AI label: %s", error.getMessage());
                }));
    }

    @Override
    public void dispose() {
        RxHelper.disposeActions(mActions);
    }

    private List<String> getVideoIds(VideoGroup videoGroup) {
        List<String> result = new ArrayList<>();

        for (Video video : videoGroup.getVideos()) {
            if (video.videoId == null) {
                continue;
            }
            // Removing from a playlist (or mix) queue would desync it from what plays next
            if (videoGroup.getType() == MediaGroup.TYPE_SUGGESTIONS && video.playlistId != null) {
                continue;
            }
            result.add(video.videoId);
        }

        return result;
    }
}
