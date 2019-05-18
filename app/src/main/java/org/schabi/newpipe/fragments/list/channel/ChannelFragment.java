package org.schabi.newpipe.fragments.list.channel;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jakewharton.rxbinding2.view.RxView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.subscription.NotificationMode;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.info_list.InfoItemDialog;
import org.schabi.newpipe.local.dialog.PlaylistAppendDialog;
import org.schabi.newpipe.local.subscription.SubscriptionService;
import org.schabi.newpipe.notifications.NotificationHelper;
import org.schabi.newpipe.player.playqueue.ChannelPlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.report.UserAction;
import org.schabi.newpipe.util.AnimationUtils;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.ImageDisplayConstants;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ShareUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

import static org.schabi.newpipe.util.AnimationUtils.animateBackgroundColor;
import static org.schabi.newpipe.util.AnimationUtils.animateTextColor;
import static org.schabi.newpipe.util.AnimationUtils.animateView;

public class ChannelFragment extends BaseListInfoFragment<ChannelInfo> {

    private final CompositeDisposable disposables = new CompositeDisposable();
    private Disposable subscribeButtonMonitor;
    private SubscriptionService subscriptionService;

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    private View headerRootLayout;
    private ImageView headerChannelBanner;
    private ImageView headerAvatarView;
    private TextView headerTitleView;
    private TextView headerSubscribersTextView;
    private Button headerSubscribeButton;
    private View playlistCtrl;

    private LinearLayout headerPlayAllButton;
    private LinearLayout headerPopupButton;
    private LinearLayout headerBackgroundButton;

    private MenuItem menuRssButton;
    private MenuItem menuNotifyButton;

    public static ChannelFragment getInstance(int serviceId, String url, String name) {
        ChannelFragment instance = new ChannelFragment();
        instance.setInitialData(serviceId, url, name);
        return instance;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if(activity != null
                && useAsFrontPage
                && isVisibleToUser) {
            setTitle(currentInfo != null ? currentInfo.getName() : name);
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        subscriptionService = SubscriptionService.getInstance(activity);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_channel, container, false);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (disposables != null) disposables.clear();
        if (subscribeButtonMonitor != null) subscribeButtonMonitor.dispose();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    protected View getListHeader() {
        headerRootLayout = activity.getLayoutInflater().inflate(R.layout.channel_header, itemsList, false);
        headerChannelBanner = headerRootLayout.findViewById(R.id.channel_banner_image);
        headerAvatarView = headerRootLayout.findViewById(R.id.channel_avatar_view);
        headerTitleView = headerRootLayout.findViewById(R.id.channel_title_view);
        headerSubscribersTextView = headerRootLayout.findViewById(R.id.channel_subscriber_view);
        headerSubscribeButton = headerRootLayout.findViewById(R.id.channel_subscribe_button);
        playlistCtrl = headerRootLayout.findViewById(R.id.playlist_control);


        headerPlayAllButton = headerRootLayout.findViewById(R.id.playlist_ctrl_play_all_button);
        headerPopupButton = headerRootLayout.findViewById(R.id.playlist_ctrl_play_popup_button);
        headerBackgroundButton = headerRootLayout.findViewById(R.id.playlist_ctrl_play_bg_button);

        return headerRootLayout;
    }

    @Override
    protected void showStreamDialog(final StreamInfoItem item) {
        final Activity activity = getActivity();
        final Context context = getContext();
        if (context == null || context.getResources() == null || getActivity() == null) return;

        final String[] commands = new String[]{
                context.getResources().getString(R.string.enqueue_on_background),
                context.getResources().getString(R.string.enqueue_on_popup),
                context.getResources().getString(R.string.start_here_on_main),
                context.getResources().getString(R.string.start_here_on_background),
                context.getResources().getString(R.string.start_here_on_popup),
                context.getResources().getString(R.string.append_playlist),
                context.getResources().getString(R.string.share)
        };

        final DialogInterface.OnClickListener actions = (DialogInterface dialogInterface, int i) -> {
            final int index = Math.max(infoListAdapter.getItemsList().indexOf(item), 0);
            switch (i) {
                case 0:
                    NavigationHelper.enqueueOnBackgroundPlayer(context, new SinglePlayQueue(item));
                    break;
                case 1:
                    NavigationHelper.enqueueOnPopupPlayer(activity, new SinglePlayQueue(item));
                    break;
                case 2:
                    NavigationHelper.playOnMainPlayer(context, getPlayQueue(index));
                    break;
                case 3:
                    NavigationHelper.playOnBackgroundPlayer(context, getPlayQueue(index));
                    break;
                case 4:
                    NavigationHelper.playOnPopupPlayer(activity, getPlayQueue(index));
                    break;
                case 5:
                    if (getFragmentManager() != null) {
                        PlaylistAppendDialog.fromStreamInfoItems(Collections.singletonList(item))
                                .show(getFragmentManager(), TAG);
                    }
                    break;
                case 6:
                    ShareUtils.shareUrl(this.getContext(), item.getName(), item.getUrl());
                    break;
                default:
                    break;
            }
        };

        new InfoItemDialog(getActivity(), item, commands, actions).show();
    }
    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        ActionBar supportActionBar = activity.getSupportActionBar();
        if(useAsFrontPage && supportActionBar != null) {
            supportActionBar.setDisplayHomeAsUpEnabled(false);
        } else {
            inflater.inflate(R.menu.menu_channel, menu);

            if (DEBUG) Log.d(TAG, "onCreateOptionsMenu() called with: menu = [" + menu +
                    "], inflater = [" + inflater + "]");
            menuRssButton = menu.findItem(R.id.menu_item_rss);
            menuNotifyButton = menu.findItem(R.id.menu_item_notify);
        }
    }

    private void openRssFeed() {
        final ChannelInfo info = currentInfo;
        if(info != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(info.getFeedUrl()));
            startActivity(intent);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_notify:
                final boolean value = !item.isChecked();
                item.setEnabled(false);
                setNotify(value);
                break;
            case R.id.menu_item_rss:
                openRssFeed();
                break;
            case R.id.menu_item_openInBrowser:
                ShareUtils.openUrlInBrowser(this.getContext(), currentInfo.getOriginalUrl());
                break;
            case R.id.menu_item_share:
                ShareUtils.shareUrl(this.getContext(), name, currentInfo.getOriginalUrl());
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Channel Subscription
    //////////////////////////////////////////////////////////////////////////*/

    private static final int BUTTON_DEBOUNCE_INTERVAL = 100;

    private void monitorSubscription(final ChannelInfo info) {
        final Consumer<Throwable> onError = (Throwable throwable) -> {
                animateView(headerSubscribeButton, false, 100);
                showSnackBarError(throwable, UserAction.SUBSCRIPTION,
                        NewPipe.getNameOfService(currentInfo.getServiceId()),
                        "Get subscription status",
                        0);
        };

        final Observable<List<SubscriptionEntity>> observable = subscriptionService.subscriptionTable()
                .getSubscription(info.getServiceId(), info.getUrl())
                .toObservable();

        disposables.add(observable
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(getSubscribeUpdateMonitor(info), onError));

        disposables.add(observable
                .map(List::isEmpty)
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(isEmpty -> updateSubscribeButton(!isEmpty), onError));


        disposables.add(observable
                .map(List::isEmpty)
                .filter(x -> isNotificationsEnabled())
                .distinctUntilChanged()
                .skip(1)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(isEmpty -> {
                    if (!isEmpty) showNotifySnackbar();
                }, onError));
    }

    private Function<Object, Object> mapOnSubscribe(final SubscriptionEntity subscription) {
        return (@NonNull Object o) -> {
            subscriptionService.subscriptionTable().insert(subscription);
            return o;
        };
    }

    private Function<Object, Object> mapOnUnsubscribe(final SubscriptionEntity subscription) {
        return (@NonNull Object o) -> {
            subscriptionService.subscriptionTable().delete(subscription);
            return o;
        };
    }

    private void updateSubscription(final ChannelInfo info) {
        if (DEBUG) Log.d(TAG, "updateSubscription() called with: info = [" + info + "]");
        final Action onComplete = () -> {
                if (DEBUG) Log.d(TAG, "Updated subscription: " + info.getUrl());
        };

        final Consumer<Throwable> onError = (@NonNull Throwable throwable) ->
                onUnrecoverableError(throwable,
                        UserAction.SUBSCRIPTION,
                        NewPipe.getNameOfService(info.getServiceId()),
                        "Updating Subscription for " + info.getUrl(),
                        R.string.subscription_update_failed);

        disposables.add(subscriptionService.updateChannelInfo(info)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(onComplete, onError));
    }

    private Disposable monitorSubscribeButton(final Button subscribeButton, final Function<Object, Object> action) {
        final Consumer<Object> onNext = (@NonNull Object o) -> {
                if (DEBUG) Log.d(TAG, "Changed subscription status to this channel!");
        };

        final Consumer<Throwable> onError = (@NonNull Throwable throwable) ->
                onUnrecoverableError(throwable,
                        UserAction.SUBSCRIPTION,
                        NewPipe.getNameOfService(currentInfo.getServiceId()),
                        "Subscription Change",
                        R.string.subscription_change_failed);

        /* Emit clicks from main thread unto io thread */
        return RxView.clicks(subscribeButton)
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(Schedulers.io())
                .debounce(BUTTON_DEBOUNCE_INTERVAL, TimeUnit.MILLISECONDS) // Ignore rapid clicks
                .map(action)
                .subscribe(onNext, onError);
    }

    private Consumer<List<SubscriptionEntity>> getSubscribeUpdateMonitor(final ChannelInfo info) {
        return (List<SubscriptionEntity> subscriptionEntities) -> {
            if (DEBUG)
                Log.d(TAG, "subscriptionService.subscriptionTable.doOnNext() called with: subscriptionEntities = [" + subscriptionEntities + "]");
            if (subscribeButtonMonitor != null) subscribeButtonMonitor.dispose();

            if (subscriptionEntities.isEmpty()) {
                if (DEBUG) Log.d(TAG, "No subscription to this channel!");
                SubscriptionEntity channel = new SubscriptionEntity();
                channel.setServiceId(info.getServiceId());
                channel.setUrl(info.getUrl());
                channel.setData(info.getName(),
                        info.getAvatarUrl(),
                        info.getDescription(),
                        info.getSubscriberCount());
                updateNotifyButton(null);
                subscribeButtonMonitor = monitorSubscribeButton(headerSubscribeButton, mapOnSubscribe(channel));
            } else {
                if (DEBUG) Log.d(TAG, "Found subscription to this channel!");
                final SubscriptionEntity subscription = subscriptionEntities.get(0);
                updateNotifyButton(subscription);
                subscribeButtonMonitor = monitorSubscribeButton(headerSubscribeButton, mapOnUnsubscribe(subscription));
            }
        };
    }

    private void updateSubscribeButton(boolean isSubscribed) {
        if (DEBUG) Log.d(TAG, "updateSubscribeButton() called with: isSubscribed = [" + isSubscribed + "]");

        boolean isButtonVisible = headerSubscribeButton.getVisibility() == View.VISIBLE;
        int backgroundDuration = isButtonVisible ? 300 : 0;
        int textDuration = isButtonVisible ? 200 : 0;

        int subscribeBackground = ContextCompat.getColor(activity, R.color.subscribe_background_color);
        int subscribeText = ContextCompat.getColor(activity, R.color.subscribe_text_color);
        int subscribedBackground = ContextCompat.getColor(activity, R.color.subscribed_background_color);
        int subscribedText = ContextCompat.getColor(activity, R.color.subscribed_text_color);

        if (!isSubscribed) {
            headerSubscribeButton.setText(R.string.subscribe_button_title);
            animateBackgroundColor(headerSubscribeButton, backgroundDuration, subscribedBackground, subscribeBackground);
            animateTextColor(headerSubscribeButton, textDuration, subscribedText, subscribeText);
        } else {
            headerSubscribeButton.setText(R.string.subscribed_button_title);
            animateBackgroundColor(headerSubscribeButton, backgroundDuration, subscribeBackground, subscribedBackground);
            animateTextColor(headerSubscribeButton, textDuration, subscribeText, subscribedText);
        }

        animateView(headerSubscribeButton, AnimationUtils.Type.LIGHT_SCALE_AND_ALPHA, true, 100);
    }

    private void updateNotifyButton(@Nullable SubscriptionEntity subscription) {
        if (menuNotifyButton == null) {
            return;
        }
        if (subscription == null) {
            menuNotifyButton.setVisible(false);
        } else {
            menuNotifyButton.setEnabled(isNotificationsEnabled());
            menuNotifyButton.setChecked(subscription.getNotificationMode() != NotificationMode.DISABLED);
            menuNotifyButton.setVisible(true);
        }
    }

    private void setNotify(boolean isEnabled) {
        final int mode = isEnabled ? NotificationMode.ENABLED_DEFAULT : NotificationMode.DISABLED;
        disposables.add(
                subscriptionService.updateNotificationMode(currentInfo.getServiceId(), currentInfo.getUrl(), mode)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe()
        );
    }

    private void showNotifySnackbar() {
        Snackbar.make(itemsList, R.string.you_successfully_subscribed, Snackbar.LENGTH_LONG)
                .setAction(R.string.get_notified, v -> setNotify(true))
                .setActionTextColor(ContextCompat.getColor(itemsList.getContext(), R.color.snackbar_action_color))
                .show();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected Single<ListExtractor.InfoItemsPage> loadMoreItemsLogic() {
        return ExtractorHelper.getMoreChannelItems(serviceId, url, currentNextPageUrl);
    }

    @Override
    protected Single<ChannelInfo> loadResult(boolean forceLoad) {
        return ExtractorHelper.getChannelInfo(serviceId, url, forceLoad);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void showLoading() {
        super.showLoading();

        imageLoader.cancelDisplayTask(headerChannelBanner);
        imageLoader.cancelDisplayTask(headerAvatarView);
        animateView(headerSubscribeButton, false, 100);
    }

    @Override
    public void handleResult(@NonNull ChannelInfo result) {
        super.handleResult(result);

        headerRootLayout.setVisibility(View.VISIBLE);
        imageLoader.displayImage(result.getBannerUrl(), headerChannelBanner,
        		ImageDisplayConstants.DISPLAY_BANNER_OPTIONS);
        imageLoader.displayImage(result.getAvatarUrl(), headerAvatarView,
        		ImageDisplayConstants.DISPLAY_AVATAR_OPTIONS);

        headerSubscribersTextView.setVisibility(View.VISIBLE);
        if (result.getSubscriberCount() >= 0) {
            headerSubscribersTextView.setText(Localization.localizeSubscribersCount(activity, result.getSubscriberCount()));
        } else {
            headerSubscribersTextView.setText(R.string.subscribers_count_not_available);
        }

        if (menuRssButton != null) menuRssButton.setVisible(!TextUtils.isEmpty(result.getFeedUrl()));

        playlistCtrl.setVisibility(View.VISIBLE);

        if (!result.getErrors().isEmpty()) {
            showSnackBarError(result.getErrors(), UserAction.REQUESTED_CHANNEL, NewPipe.getNameOfService(result.getServiceId()), result.getUrl(), 0);
        }

        if (disposables != null) disposables.clear();
        if (subscribeButtonMonitor != null) subscribeButtonMonitor.dispose();
        updateSubscription(result);
        monitorSubscription(result);

        headerPlayAllButton.setOnClickListener(
                view -> NavigationHelper.playOnMainPlayer(activity, getPlayQueue()));
        headerPopupButton.setOnClickListener(
                view -> NavigationHelper.playOnPopupPlayer(activity, getPlayQueue()));
        headerBackgroundButton.setOnClickListener(
                view -> NavigationHelper.playOnBackgroundPlayer(activity, getPlayQueue()));
    }

    private PlayQueue getPlayQueue() {
        return getPlayQueue(0);
    }

    private PlayQueue getPlayQueue(final int index) {
        final List<StreamInfoItem> streamItems = new ArrayList<>();
        for(InfoItem i : infoListAdapter.getItemsList()) {
            if(i instanceof StreamInfoItem) {
                streamItems.add((StreamInfoItem) i);
            }
        }
        return new ChannelPlayQueue(
                currentInfo.getServiceId(),
                currentInfo.getUrl(),
                currentInfo.getNextPageUrl(),
                streamItems,
                index
        );
    }

    @Override
    public void handleNextItems(ListExtractor.InfoItemsPage result) {
        super.handleNextItems(result);

        if (!result.getErrors().isEmpty()) {
            showSnackBarError(result.getErrors(),
                    UserAction.REQUESTED_CHANNEL,
                    NewPipe.getNameOfService(serviceId),
                    "Get next page of: " + url,
                    R.string.general_error);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // OnError
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected boolean onError(Throwable exception) {
        if (super.onError(exception)) return true;

        int errorId = exception instanceof ExtractionException ? R.string.parsing_error : R.string.general_error;
        onUnrecoverableError(exception,
                UserAction.REQUESTED_CHANNEL,
                NewPipe.getNameOfService(serviceId),
                url,
                errorId);
        return true;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void setTitle(String title) {
        super.setTitle(title);
        if (!useAsFrontPage) headerTitleView.setText(title);
    }

    private boolean isNotificationsEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean(getString(R.string.enable_streams_notifications), false)
                && NotificationHelper.isNotificationsEnabledNative(getContext());
    }
}
