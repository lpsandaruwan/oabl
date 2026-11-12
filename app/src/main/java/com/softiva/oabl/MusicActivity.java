package com.softiva.oabl;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Library-first music UI: split-friendly MiniPlayer, artwork-led Now Playing, native controls. */
public final class MusicActivity extends Activity {
    private static final int FOLDER = 800;
    private MusicLibrary library;
    private LauncherTheme.Palette palette;
    private AutoThemeWatcher theme;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<MusicLibrary.Track> tracks = new ArrayList<>();
    private final ArrayList<MusicLibrary.BrowseGroup> groups = new ArrayList<>();
    private String collection = "All", sort = "Artist", search = "";
    private String browseKey, browseRoot, parentSearch = "";
    private long playlist;
    /** The playing queue this screen last switched to (its hash), so it follows each queue once. */
    private long shownSource;
    private boolean scrollToCurrent;
    /** The playing list's title: its source's name when known, else "Now Playing". */
    private String nowPlayingTitle = "Now Playing";
    private int queryGeneration;
    private boolean visible, seeking, expanded, compact, showingGroups, querying;
    private long renderedTrack = -1;
    private boolean renderedPlaying;
    private final Map<String, TextView> tabsByName = new HashMap<>();
    private TextView title, artist, detail, elapsed, remaining, count, scanState, collectionLabel, playAll, shuffle, sortButton, empty;
    private MusicIconView play, favorite, repeat, collectionBack, headerBack;
    private EditText searchField;
    private SeekBar progress;
    private CoverView cover;
    private ListView list;
    private TrackAdapter adapter;
    private GridView albumGrid;
    private AlbumAdapter albumAdapter;
    private TextView favouritesChip, recentChip, crossfadeChip, bassChip, headroomChip, upNext;
    private LinearLayout nowPanel;
    private final ArrayList<TextView> nowTexts = new ArrayList<>();
    private final ArrayList<MusicIconView> nowIcons = new ArrayList<>();
    private final ArrayList<TextView> nowChips = new ArrayList<>();
    private boolean effects = true, nowOnArt;
    private int artColor;
    private final Map<Long, String> qualities = new HashMap<>();
    private android.window.OnBackInvokedCallback backCallback;
    private final Runnable reload = ()->{this.trackCache.clear();query();};
    private final Runnable playbackChanged = this::renderPlayback;
    private final Runnable progressTick = this::renderProgress;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        library = MusicLibrary.get(this);
        effects = new LauncherPreferences(this).visualEffects();
        palette = LauncherTheme.resolve(this, new LauncherPreferences(this));
        if (state != null) {
            collection = state.getString("collection", "All"); sort = state.getString("sort", "Artist");
            search = state.getString("search", ""); playlist = state.getLong("playlist");
            expanded = state.getBoolean("expanded");
            browseKey = state.getString("browseKey"); browseRoot = state.getString("browseRoot");
            parentSearch = state.getString("parentSearch", "");
            shownSource = state.getLong("shownSource");
            nowPlayingTitle = state.getString("nowPlayingTitle", "Now Playing");
        } else followPlayingSource();
        theme = new AutoThemeWatcher(this, () -> {
            LauncherTheme.Palette next = LauncherTheme.resolve(this, new LauncherPreferences(this));
            if (next.light != palette.light || next.accent != palette.accent) recreate();
        });
        getWindow().setNavigationBarColor(palette.root);
        getWindow().setStatusBarColor(palette.root);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 29) getWindow().getDecorView().setForceDarkAllowed(false);
        build(); query(); renderPlayback();
        if(Build.VERSION.SDK_INT>=33){
            backCallback=this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);
        }
    }
    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b); b.putString("collection", collection); b.putString("sort", sort);
        b.putString("search", search); b.putLong("playlist", playlist); b.putBoolean("expanded", expanded);
        b.putString("browseKey", browseKey); b.putString("browseRoot", browseRoot); b.putString("parentSearch", parentSearch);
        b.putLong("shownSource", shownSource); b.putString("nowPlayingTitle", nowPlayingTitle);
    }
    @Override protected void onResume() {
        super.onResume(); visible = true; DevicePerformance.apply(this, getWindow());
        PlaylistImport.applyPending(this);
        trackCache.clear(); // a star or a rescan elsewhere may have changed them
        followPlayingSource();
        library.observe(reload); MusicClient.get(this).observe(playbackChanged); theme.start(); query(); renderPlayback();
    }
    @Override protected void onPause() {
        visible = false; library.unobserve(reload); MusicClient.get(this).unobserve(playbackChanged);
        theme.stop(); main.removeCallbacksAndMessages(null); super.onPause();
    }
    @Override public boolean onKeyUp(int code,KeyEvent event) {
        if(code==KeyEvent.KEYCODE_BACK&&((compact&&expanded)||insideGroup())){navigateBack();return true;}
        return super.onKeyUp(code,event);
    }
    @Override protected void onDestroy(){
        if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        main.removeCallbacksAndMessages(null);super.onDestroy();
    }

    private void build() {
        queryGeneration++;
        compact = getResources().getConfiguration().screenWidthDp < 850;
        title = artist = detail = elapsed = remaining = null;
        play = favorite = repeat = null; progress = null; cover = null;
        count=scanState=collectionLabel=null;adapter=null;list=null;
        collectionBack=null;searchField=null;playAll=shuffle=sortButton=empty=null;
        albumGrid=null;albumAdapter=null;favouritesChip=null;upNext=null;nowPanel=null;nowOnArt=false;
        nowTexts.clear();nowIcons.clear();nowChips.clear();
        tabsByName.clear();
        LinearLayout page = column();
        page.setTag("music_page");
        page.setPadding(dp(compact ? 16 : 28), dp(14), dp(compact ? 16 : 28), dp(16));
        page.setBackgroundColor(palette.root);
        setContentView(page);
        ImmersiveWindow.apply(getWindow(), page);

        LinearLayout header = row();
        headerBack = icon(compact && expanded ? MusicIconView.Icon.DOWN : MusicIconView.Icon.BACK,
                compact && expanded ? "Back to Library" : "Back to dashboard",
                this::navigateBack);
        header.addView(headerBack, lp(48, 48));
        TextView heading = text(compact && expanded ? "Now Playing" : "Library", compact ? 27 : 32, palette.ink);
        heading.setTypeface(null, Typeface.BOLD); heading.setPadding(dp(12), 0, 0, 0);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(60), 1));
        header.addView(icon(MusicIconView.Icon.FOLDER, "Music folders", this::folderMenu), lp(48,48));
        header.addView(icon(MusicIconView.Icon.MORE, "Music options", this::compactOptions), lp(48,48));
        page.addView(header);
        space(page, 12);

        if (compact && expanded) {
            page.addView(nowPlaying(), new LinearLayout.LayoutParams(-1, 0, 1));
        } else if (compact) {
            page.addView(browser(), new LinearLayout.LayoutParams(-1, 0, 1));
            space(page, 12);
            page.addView(miniPlayer(), lp(-1, 80));
        } else {
            LinearLayout body = row(); body.setGravity(Gravity.TOP);
            body.addView(browser(), new LinearLayout.LayoutParams(0, -1, .58f));
            View gap = new View(this); body.addView(gap, lp(28, 1));
            body.addView(nowPlaying(), new LinearLayout.LayoutParams(0, -1, .42f));
            page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    private LinearLayout browser() {
        LinearLayout browser = column(); browser.setTag("music_browser");
        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        // Favourites is a filter inside Songs, and Playing Next lives on the Now Playing panel.
        String[] keys = {"All", "Artists", "Albums", "Folders", "Playlists"};
        String[] labels = {"Songs", "Artists", "Albums", "Folders", "Playlists"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            TextView tab = button(labels[i], "Show " + labels[i], () -> selectCollection(key));
            tab.setPadding(dp(14),0,dp(14),0);
            tab.setTag("music_tab_"+key);
            tabsByName.put(key, tab);
            LinearLayout.LayoutParams size = lp(-2,48); size.rightMargin=dp(4);
            tabs.addView(tab,size);
        }
        strip.addView(tabs); browser.addView(strip); space(browser,12);

        LinearLayout searchRow = row();
        searchRow.setPadding(dp(4),0,dp(8),0);
        searchRow.setBackground(LauncherTheme.rounded(this,palette.light?0xffe8edf1:0xff19212b,14,0,0));
        MusicIconView searchIcon=icon(MusicIconView.Icon.SEARCH,"Search",()->{});
        searchIcon.setClickable(false); searchIcon.setFocusable(false);
        searchRow.addView(searchIcon,lp(44,48));
        EditText field=new EditText(this); searchField=field;
        field.setTag("music_search"); field.setSingleLine(true); field.setTextSize(15);
        field.setTextColor(palette.ink); field.setHintTextColor(palette.muted);
        field.setHint("Search your library"); field.setContentDescription("Search your library");
        field.setBackground(null); field.setPadding(0,0,dp(8),0); field.setText(search);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int a,int c,int f){}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c){
                search=s.toString(); main.removeCallbacks(reload); main.postDelayed(reload,180);
            }
            @Override public void afterTextChanged(Editable e){}
        });
        searchRow.addView(field,new LinearLayout.LayoutParams(0,dp(48),1));
        sortButton=button(sort, "Sort songs by "+sort,this::sortMenu);
        sortButton.setTextColor(palette.accent); searchRow.addView(sortButton,lp(112,48));
        browser.addView(searchRow); space(browser,16);

        LinearLayout section=row();
        collectionBack=icon(MusicIconView.Icon.BACK,"Back to Artists",this::backToGroups);
        collectionBack.setTag("music_collection_back");collectionBack.setVisibility(View.GONE);
        LinearLayout.LayoutParams backSize=lp(48,48);backSize.rightMargin=dp(8);
        section.addView(collectionBack,backSize);
        LinearLayout names=column();
        collectionLabel=text("Songs",25,palette.ink); collectionLabel.setTypeface(null,Typeface.BOLD);
        collectionLabel.setTag("music_collection_title");
        collectionLabel.setSingleLine(true); collectionLabel.setEllipsize(TextUtils.TruncateAt.END);
        count=text("",12,palette.muted);count.setTag("music_collection_count");names.addView(collectionLabel); names.addView(count);
        section.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        favouritesChip=pill("Favourites",R.drawable.ic_music_star,false,()->selectCollection("Stars".equals(collection)?"All":"Stars"));
        favouritesChip.setTag("music_favourites_filter");
        LinearLayout.LayoutParams chipSize=lp(-2,40); chipSize.rightMargin=dp(8);
        LinearLayout browseActions = compact ? row() : section;
        browseActions.addView(favouritesChip,chipSize);
        recentChip=pill("Recent",R.drawable.ic_nav_recent,false,()->selectCollection("Recent".equals(collection)?"All":"Recent"));
        recentChip.setTag("music_recent_filter"); recentChip.setContentDescription("Recently played");
        LinearLayout.LayoutParams recentSize=lp(-2,40); recentSize.rightMargin=dp(8);
        browseActions.addView(recentChip,recentSize);
        playAll=pill("Play",R.drawable.ic_media_play,true,()->playSelection(false));
        playAll.setTag("music_play_all"); playAll.setContentDescription("Play these songs");
        browseActions.addView(playAll,lp(-2,40));
        shuffle=pill("Shuffle",R.drawable.ic_music_shuffle,false,()->playSelection(true));shuffle.setTag("music_shuffle_all");
        shuffle.setContentDescription("Shuffle these songs");
        LinearLayout.LayoutParams shuffleSize=lp(-2,40); shuffleSize.leftMargin=dp(8);
        browseActions.addView(shuffle,shuffleSize); browser.addView(section);
        if (compact) {
            space(browser,8);
            HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
            actionsScroll.setHorizontalScrollBarEnabled(false);
            actionsScroll.setHorizontalFadingEdgeEnabled(true);
            actionsScroll.addView(browseActions); browser.addView(actionsScroll, lp(-1, 44));
        }
        space(browser,10);

        FrameLayout results=new FrameLayout(this);
        list=new ListView(this); list.setTag("music_tracks");
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(2)); list.setCacheColorHint(Color.TRANSPARENT); list.setSelector(LauncherTheme.dockItem(this,palette));
        adapter=new TrackAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener((p,v,pos,id)->{
            if(querying)return;
            if(showingGroups){if(pos<groups.size())openGroup(groups.get(pos));}
            else if(pos<tracks.size()){playFromHere(new ArrayList<>(tracks),tracks.get(pos).id);hideKeyboard();}
        });
        list.setOnItemLongClickListener((p,v,pos,id)->{if(querying||showingGroups||pos>=tracks.size())return false;trackMenu(tracks.get(pos));return true;});
        results.addView(list,new FrameLayout.LayoutParams(-1,-1));
        empty=text("No Songs\nChoose a music folder to get started.",16,palette.muted);
        empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20),dp(20),dp(20),dp(20));
        empty.setOnClickListener(v->folderMenu());
        results.addView(empty,new FrameLayout.LayoutParams(-1,-1)); empty.setVisibility(View.GONE);
        albumGrid=new GridView(this); albumGrid.setTag("music_albums");
        albumGrid.setNumColumns(GridView.AUTO_FIT); albumGrid.setColumnWidth(dp(150));
        albumGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        albumGrid.setHorizontalSpacing(dp(14)); albumGrid.setVerticalSpacing(dp(18));
        albumGrid.setSelector(LauncherTheme.dockItem(this,palette)); albumGrid.setDrawSelectorOnTop(false);
        albumGrid.setClipToPadding(false); albumGrid.setPadding(0,dp(4),0,dp(12));
        albumAdapter=new AlbumAdapter(); albumGrid.setAdapter(albumAdapter);
        albumGrid.setOnItemClickListener((p,v,pos,id)->{if(!querying&&pos<groups.size())openGroup(groups.get(pos));});
        albumGrid.setVisibility(View.GONE);
        results.addView(albumGrid,new FrameLayout.LayoutParams(-1,-1));
        browser.addView(results,new LinearLayout.LayoutParams(-1,0,1));
        scanState=text("",12,palette.muted); scanState.setMaxLines(2); scanState.setVisibility(View.GONE);
        browser.addView(scanState,lp(-1,-2));
        return browser;
    }

    private LinearLayout nowPlaying() {
        LinearLayout now=column(); now.setTag("music_now_playing"); nowPanel=now;
        now.setPadding(dp(22),dp(10),dp(22),dp(14));
        now.setBackground(defaultNowBackground());
        now.setOutlineProvider(new ViewOutlineProvider(){
            @Override public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),dp(24));}
        });
        now.setClipToOutline(true);

        // Artwork/metadata can scroll on short displays; transport and playlist actions stay put.
        ScrollView infoScroll = new ScrollView(this);
        infoScroll.setTag("now_information_scroll");
        infoScroll.setFillViewport(true);
        infoScroll.setVerticalScrollBarEnabled(false);
        LinearLayout informationPanel = column();
        infoScroll.addView(informationPanel);
        now.addView(infoScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout labelRow=row();
        TextView label=text("NOW PLAYING",11,palette.muted); label.setLetterSpacing(.14f); medium(label);
        nowTexts.add(label);
        labelRow.addView(label,new LinearLayout.LayoutParams(0,dp(30),1));
        crossfadeChip=text("",11,palette.accent); crossfadeChip.setSingleLine(true); medium(crossfadeChip);
        crossfadeChip.setGravity(Gravity.CENTER); crossfadeChip.setPadding(dp(10),0,dp(10),0); crossfadeChip.setLetterSpacing(.06f);
        crossfadeChip.setTag("now_crossfade");
        crossfadeChip.setOnClickListener(v->CrossfadeDialog.show(this,palette,this::renderPlayback));
        LinearLayout.LayoutParams crossfadeSize=lp(-2,24); crossfadeSize.rightMargin=dp(8);
        labelRow.addView(crossfadeChip,crossfadeSize);
        detail=text("LOCAL AUDIO",11,palette.accent); detail.setSingleLine(true); medium(detail);
        detail.setGravity(Gravity.CENTER); detail.setPadding(dp(10),0,dp(10),0); detail.setLetterSpacing(.06f);
        detail.setTag("now_quality");
        labelRow.addView(detail,lp(-2,24)); informationPanel.addView(labelRow);
        // The two sound switches that matter while listening; the sliders and the rest stay in the equalizer.
        LinearLayout soundRow=row();
        bassChip=soundChip("now_bass",v->cycleBass());
        headroomChip=soundChip("now_headroom",v->toggleHeadroom());
        LinearLayout.LayoutParams chipGap=lp(-2,24); chipGap.rightMargin=dp(8);
        soundRow.addView(bassChip,chipGap);
        soundRow.addView(headroomChip,lp(-2,24));
        LinearLayout.LayoutParams soundSize=new LinearLayout.LayoutParams(-1,-2); soundSize.topMargin=dp(2);
        informationPanel.addView(soundRow,soundSize);
        cover=new CoverView(true); cover.setTag("now_artwork");
        installCoverGestures(cover);
        int artHeight = Math.max(100, Math.min(220, getResources().getConfiguration().screenHeightDp - 430));
        LinearLayout.LayoutParams coverSize=lp(-1,artHeight); coverSize.setMargins(0,dp(2),0,dp(2));
        informationPanel.addView(cover,coverSize);
        space(informationPanel,8);

        LinearLayout information=row(); information.setGravity(Gravity.TOP);
        LinearLayout names=column();
        title=text("Not Playing",compact?23:26,palette.ink); medium(title);
        title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
        artist=text("Choose a song from your library",14,palette.muted);
        artist.setMaxLines(1); artist.setEllipsize(TextUtils.TruncateAt.END); artist.setPadding(0,dp(4),0,0);
        names.addView(title); names.addView(artist);
        information.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        favorite=icon(MusicIconView.Icon.STAR,"Add to Favourite Songs",this::starCurrent);
        favorite.setTag("now_favorite"); nowIcons.add(favorite);
        information.addView(favorite,lp(48,48)); informationPanel.addView(information);
        space(informationPanel,4);

        progress=new SeekBar(this); progress.setTag("music_progress");
        progress.setMax(1000); progress.setContentDescription("Playback position");
        progress.setPadding(dp(10),0,dp(10),0); progress.setSplitTrack(false);
        styleSeek(progress,palette.accent,(palette.muted&0x00ffffff)|0x40000000);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int value,boolean user){
                if(user)elapsed.setText(time(currentDuration()*value/1000));
            }
            @Override public void onStartTrackingTouch(SeekBar b){seeking=true;}
            @Override public void onStopTrackingTouch(SeekBar b){
                if(currentDuration()>0)MusicClient.get(MusicActivity.this).seek(currentDuration()*b.getProgress()/1000);
                seeking=false;
            }
        });
        LinearLayout.LayoutParams seekSize=lp(-1,40); seekSize.setMargins(-dp(10),0,-dp(10),0);
        informationPanel.addView(progress,seekSize);
        LinearLayout times=row(); elapsed=text("0:00",12,palette.muted); remaining=text("—",12,palette.muted);
        elapsed.setFontFeatureSettings("tnum"); remaining.setFontFeatureSettings("tnum");
        remaining.setGravity(Gravity.END|Gravity.CENTER_VERTICAL); nowTexts.add(elapsed); nowTexts.add(remaining);
        times.addView(elapsed,new LinearLayout.LayoutParams(0,dp(18),1)); times.addView(remaining,lp(72,18)); informationPanel.addView(times);
        space(now,4);

        // Shuffle and repeat are quieter modes; previous, play and next are the main controls.
        LinearLayout transport=row(); transport.setGravity(Gravity.CENTER);
        MusicIconView shuffleMode=icon(MusicIconView.Icon.SHUFFLE,"Shuffle Playing Next",this::shuffleQueue);
        shuffleMode.colors(palette.muted,palette.accent);
        transport.addView(shuffleMode,lp(48,48));
        MusicIconView previous=icon(MusicIconView.Icon.PREVIOUS,"Previous song",()->command("previous")); nowIcons.add(previous);
        LinearLayout.LayoutParams sideSize=lp(60,64); sideSize.leftMargin=dp(14);
        transport.addView(previous,sideSize);
        play=primaryPlay();
        LinearLayout.LayoutParams playSize=lp(76,76); playSize.setMargins(dp(14),0,dp(14),0);
        transport.addView(play,playSize);
        MusicIconView nextSong=icon(MusicIconView.Icon.NEXT,"Next song",()->command("next")); nowIcons.add(nextSong);
        LinearLayout.LayoutParams nextSize=lp(60,64); nextSize.rightMargin=dp(14);
        transport.addView(nextSong,nextSize);
        repeat=icon(MusicIconView.Icon.REPEAT,"Repeat off",this::cycleRepeat);
        repeat.colors(palette.muted,palette.accent);
        transport.addView(repeat,lp(48,48)); now.addView(transport);
        shuffleMode.setTag("now_shuffle_mode");

        upNext=text("",12,palette.muted); upNext.setGravity(Gravity.CENTER); upNext.setSingleLine(true);
        upNext.setEllipsize(TextUtils.TruncateAt.END); upNext.setTag("now_up_next"); nowTexts.add(upNext);
        LinearLayout.LayoutParams upSize=lp(-1,20); upSize.topMargin=dp(4);
        now.addView(upNext,upSize);
        space(now,6);

        LinearLayout actions=row(); actions.setGravity(Gravity.CENTER);
        TextView add=pill("Add to Playlist",R.drawable.ic_music_playlist_add,false,()->{
            MusicLibrary.Track t=current();if(t!=null)addToPlaylist(t);else toast("Choose a song first");
        }); add.setTag("now_add_playlist"); nowChips.add(add);
        actions.addView(add,lp(-2,40));
        TextView next=pill("Playing Next",R.drawable.ic_music_queue,false,()->{
            clearBrowse();setSearch("");collection="Queue";playlist=0;if(compact)showPlayer(false);else query();
        }); next.setContentDescription("Show Playing Next"); nowChips.add(next);
        LinearLayout.LayoutParams nextChip=lp(-2,40); nextChip.leftMargin=dp(10);
        actions.addView(next,nextChip); now.addView(actions);
        recolourNow();
        return now;
    }

    private Drawable defaultNowBackground(){
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,palette.light
                ? new int[]{0xffe9eef2,0xfffdfefe} : new int[]{0xff1c2733,0xff111923});
    }

    /** Fills the Now Playing panel with a blurred, colour-matched copy of the artwork. */
    private void applyNowArt(Bitmap art){
        if(nowPanel==null)return;
        if(art==null){
            if(!nowOnArt)return;
            nowOnArt=false;crossfadeNow(defaultNowBackground());recolourNow();return;
        }
        Bitmap blurred=ImageBlur.blurred(art,48,48,4);
        artColor=ImageBlur.vibrant(art);
        int top=ImageBlur.mix(artColor,Color.BLACK,.30f),bottom=ImageBlur.mix(artColor,Color.BLACK,.66f);
        GradientDrawable scrim=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{(top&0x00ffffff)|0xa8000000,(bottom&0x00ffffff)|0xee000000});
        crossfadeNow(new android.graphics.drawable.LayerDrawable(new Drawable[]{
                new android.graphics.drawable.BitmapDrawable(getResources(),blurred),scrim}));
        nowOnArt=true;recolourNow();
    }

    private void crossfadeNow(Drawable next){
        Drawable previous=nowPanel.getBackground();
        if(previous instanceof android.graphics.drawable.TransitionDrawable)
            previous=((android.graphics.drawable.TransitionDrawable)previous).getDrawable(1);
        if(!effects||previous==null){nowPanel.setBackground(next);return;}
        android.graphics.drawable.TransitionDrawable fade=new android.graphics.drawable.TransitionDrawable(new Drawable[]{previous,next});
        fade.setCrossFadeEnabled(true);nowPanel.setBackground(fade);fade.startTransition(450);
    }

    /** White text and controls over artwork; theme colours otherwise. */
    private void recolourNow(){
        if(nowPanel==null)return;
        int ink=nowOnArt?Color.WHITE:palette.ink,muted=nowOnArt?0xccffffff:palette.muted,accent=nowOnArt?Color.WHITE:palette.accent;
        title.setTextColor(ink);artist.setTextColor(muted);
        for(TextView v:nowTexts)v.setTextColor(muted);
        for(MusicIconView v:nowIcons)v.colors(ink,accent);
        if(repeat!=null)repeat.colors(muted,accent);
        MusicIconView shuffleMode=nowPanel.findViewWithTag("now_shuffle_mode");if(shuffleMode!=null)shuffleMode.colors(muted,accent);
        detail.setTextColor(nowOnArt?Color.WHITE:palette.accent);
        detail.setBackground(LauncherTheme.rounded(this,nowOnArt?0x2effffff:(palette.accent&0x00ffffff)|0x1c000000,12,0,0));
        for(TextView chip:new TextView[]{crossfadeChip,bassChip,headroomChip}){
            if(chip==null)continue;
            chip.setTextColor(nowOnArt?Color.WHITE:palette.accent);
            chip.setBackground(LauncherTheme.rounded(this,nowOnArt?0x2effffff:(palette.accent&0x00ffffff)|0x1c000000,12,0,0));
        }
        for(TextView chip:nowChips)stylePill(chip,false);
        if(nowOnArt){
            play.colors(ImageBlur.mix(artColor,Color.BLACK,.55f),Color.WHITE);
            play.setBackground(LauncherTheme.rounded(this,Color.WHITE,38,0,0));
        }else{
            play.colors(palette.light?Color.WHITE:palette.root,palette.accent);
            play.setBackground(LauncherTheme.rounded(this,palette.accent,38,0,0));
        }
        styleSeek(progress,accent,nowOnArt?0x40ffffff:(palette.muted&0x00ffffff)|0x40000000);
    }

    /** Thick rounded track with a large thumb that is easy to hit on a car screen. */
    private void styleSeek(SeekBar bar,int fill,int track){
        if(bar==null)return;
        GradientDrawable background=new GradientDrawable();background.setColor(track);background.setCornerRadius(dp(3));
        GradientDrawable played=new GradientDrawable();played.setColor(fill);played.setCornerRadius(dp(3));
        android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new Drawable[]{
                background,new android.graphics.drawable.ClipDrawable(played,Gravity.START,android.graphics.drawable.ClipDrawable.HORIZONTAL)});
        layers.setId(0,android.R.id.background);layers.setId(1,android.R.id.progress);
        // The track runs between the thumb's centre at 0:00 and at the end, so nothing pokes out past the dot.
        for(int i=0;i<2;i++){layers.setLayerGravity(i,Gravity.CENTER_VERTICAL|Gravity.FILL_HORIZONTAL);layers.setLayerHeight(i,dp(6));layers.setLayerInsetLeft(i,dp(6));layers.setLayerInsetRight(i,dp(6));}
        int value=bar.getProgress();
        bar.setProgressTintList(null);bar.setProgressBackgroundTintList(null);bar.setThumbTintList(null);
        bar.setProgressDrawable(layers);
        GradientDrawable thumb=new GradientDrawable();thumb.setShape(GradientDrawable.OVAL);thumb.setColor(fill);thumb.setSize(dp(18),dp(18));
        bar.setThumb(thumb);bar.setProgress(0);bar.setProgress(value);
    }

    private TextView pill(String value,int glyph,boolean primary,Runnable action){
        TextView v=text(value,13,palette.ink);medium(v);v.setGravity(Gravity.CENTER);v.setSingleLine(true);
        v.setPadding(dp(14),0,dp(16),0);v.setCompoundDrawablePadding(dp(7));v.setTag(R.id.music_pill_primary,primary);
        Drawable icon=getDrawable(glyph).mutate();icon.setBounds(0,0,dp(18),dp(18));
        v.setCompoundDrawablesRelative(icon,null,null,null);
        v.setOnClickListener(x->action.run());v.setFocusable(true);v.setContentDescription(value);
        stylePill(v,false);return v;
    }

    private void stylePill(TextView v,boolean selected){
        if(v==null)return;
        boolean primary=Boolean.TRUE.equals(v.getTag(R.id.music_pill_primary));
        boolean onArt=nowOnArt&&nowChips.contains(v);
        int fill,ink;
        if(primary){fill=palette.accent;ink=palette.light?Color.WHITE:palette.root;}
        else if(onArt){fill=0x2effffff;ink=Color.WHITE;}
        else if(selected){fill=(palette.accent&0x00ffffff)|0x26000000;ink=palette.accent;}
        else{fill=palette.light?0x10142e38:0x14ffffff;ink=palette.ink;}
        v.setTextColor(ink);
        Drawable[] icons=v.getCompoundDrawablesRelative();if(icons[0]!=null)icons[0].setTint(primary||onArt?ink:palette.accent);
        v.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.light?0x22142e38:0x33ffffff),
                LauncherTheme.rounded(this,fill,20,0,0),null));
        v.setSelected(selected);
    }

    private static void medium(TextView v){v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));}

    /** Shows the folder in place of a missing or placeholder artist. */
    private static String artistLine(MusicLibrary.Track t){
        String who=t.artist==null?"":t.artist.trim();
        if(who.isEmpty()||"unknown artist".equalsIgnoreCase(who)){
            // Untagged: in an Artist/Album folder layout the parent folder names the artist.
            String[] parts=(t.folder==null?"":t.folder).split("/");
            String leaf=parts[parts.length-1];
            who=parts.length>=2&&leaf.equalsIgnoreCase(t.album)?parts[parts.length-2]:leaf;
            if(who.isEmpty())who="Unknown artist";
        }
        return who+(t.album.isEmpty()||t.album.equalsIgnoreCase(who)?"":" · "+t.album);
    }

    /** Format plus sample rate or bitrate, read once per song in the background. */
    private String qualityLabel(MusicLibrary.Track t){
        String quality=qualities.get(t.id);
        if(!qualities.containsKey(t.id)){
            qualities.put(t.id,null);final long id=t.id;final String uri=t.uri;
            MusicLibrary.WORK.execute(()->{
                String value=readQuality(uri);
                main.post(()->{qualities.put(id,value==null?"":value);if(!ActivityState.isGone(this))renderPlayback();});
            });
        }
        return format(t)+(quality==null||quality.isEmpty()?"":" · "+quality);
    }

    private String readQuality(String uri){
        android.media.MediaMetadataRetriever reader=new android.media.MediaMetadataRetriever();
        try{
            reader.setDataSource(this,Uri.parse(uri));
            if(Build.VERSION.SDK_INT>=31){
                String rate=reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
                if(rate!=null&&!rate.isEmpty()){
                    float khz=Integer.parseInt(rate)/1000f;
                    return (khz==Math.round(khz)?String.valueOf(Math.round(khz)):String.format(Locale.US,"%.1f",khz))+" kHz";
                }
            }
            String bitrate=reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE);
            return bitrate==null||bitrate.isEmpty()?null:Math.round(Integer.parseInt(bitrate)/1000f)+" kbps";
        }catch(RuntimeException e){return null;}
        finally{try{reader.release();}catch(Exception ignored){}}
    }

    private LinearLayout miniPlayer() {
        LinearLayout mini=row(); mini.setTag("music_mini_player");
        mini.setPadding(dp(12),dp(8),dp(4),dp(8));
        mini.setBackground(LauncherTheme.rounded(this,palette.light?0xffe5ebef:0xff1c2834,20,0,0));
        mini.setOnClickListener(v->showPlayer(true)); mini.setContentDescription("Open Now Playing"); mini.setFocusable(true);
        cover=new CoverView(false);mini.addView(cover,lp(56,56));
        LinearLayout names=column();names.setPadding(dp(12),0,dp(4),0);names.setGravity(Gravity.CENTER_VERTICAL);
        title=text("Not Playing",16,palette.ink);title.setTypeface(null,Typeface.BOLD);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);
        artist=text("Choose a song",12,palette.muted);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(title);names.addView(artist);mini.addView(names,new LinearLayout.LayoutParams(0,-1,1));
        favorite=icon(MusicIconView.Icon.STAR,"Add to Favourite Songs",this::starCurrent);favorite.setTag("now_favorite");mini.addView(favorite,lp(48,56));
        play=icon(MusicIconView.Icon.PLAY,"Play",this::togglePlayback);play.setTag("music_play_pause");mini.addView(play,lp(52,56));
        mini.addView(icon(MusicIconView.Icon.NEXT,"Next song",()->command("next")),lp(48,56));
        return mini;
    }
    private MusicIconView primaryPlay() {
        MusicIconView v=new MusicIconView(this,MusicIconView.Icon.PLAY,palette.light?Color.WHITE:palette.root,palette.accent,"Play",this::togglePlayback);
        v.setTag("music_play_pause");v.setBackground(LauncherTheme.rounded(this,palette.accent,32,0,0));return v;
    }
    private MusicIconView icon(MusicIconView.Icon glyph,String label,Runnable action) {
        MusicIconView v=new MusicIconView(this,glyph,palette.ink,palette.accent,label,action);
        v.setBackground(LauncherTheme.dockItem(this,palette));return v;
    }
    private void showPlayer(boolean value) { expanded=value;build();query();renderPlayback(); }
    private boolean groupedCollection(){return "Artists".equals(collection)||"Albums".equals(collection)||"Folders".equals(collection);}
    private boolean insideGroup(){return groupedCollection()&&browseKey!=null;}
    private void navigateBack(){if(compact&&expanded)showPlayer(false);else if(insideGroup())backToGroups();else finish();}
    private void openGroup(MusicLibrary.BrowseGroup group){
        parentSearch=search;browseKey=group.key;browseRoot=group.root;
        setSearch("");hideKeyboard();query();if(list!=null)list.setSelection(0);
    }
    private void backToGroups(){
        if(!insideGroup())return;browseKey=browseRoot=null;setSearch(parentSearch);parentSearch="";
        hideKeyboard();query();if(list!=null)list.setSelection(0);
    }
    /** Plays these songs and remembers the name of the list they came from (Playing Next isn't one). */
    private void playFromHere(List<MusicLibrary.Track> songs,long id){
        if(!"Queue".equals(collection)&&collectionLabel!=null)MusicClient.rememberSource(this,collectionLabel.getText().toString(),songs.size());
        MusicClient.get(this).play(songs,id);
    }
    /**
     * Whenever there's a playing queue this screen hasn't shown yet (it just opened, or something new
     * started, from here, from Home or anywhere else), show that queue in playing order, titled with the
     * list it came from, and scroll to the playing song. Browsing elsewhere afterwards isn't
     * overridden: each queue is followed once.
     */
    private void followPlayingSource(){
        List<Long> queue=MusicClient.get(this).queue();
        if(queue.isEmpty()||MusicClient.get(this).currentId()<0)return;
        long key=queue.hashCode()*31L+queue.size();
        if(key==shownSource)return;
        shownSource=key;
        android.content.SharedPreferences view=getSharedPreferences(MusicClient.VIEW,MODE_PRIVATE);
        String name=view.getString("name",null);
        nowPlayingTitle=name!=null&&view.getInt("count",-1)==queue.size()?name:"Now Playing";
        collection="Queue";playlist=0;browseKey=browseRoot=null;
        parentSearch="";search="";if(searchField!=null)searchField.setText("");
        scrollToCurrent=true;
    }
    private void setSearch(String value){search=value;if(searchField!=null&&!value.contentEquals(searchField.getText()))searchField.setText(value);main.removeCallbacks(reload);}
    private void clearBrowse(){if(insideGroup())setSearch("");browseKey=browseRoot=null;parentSearch="";}
    private void playSelection(boolean shuffled) {
        if(querying)return;
        if(groupedCollection()&&!insideGroup()){toast("Choose an artist, album or folder first");return;}
        if(tracks.isEmpty()){toast("Choose a music folder or change your search");return;}
        ArrayList<MusicLibrary.Track> selected=new ArrayList<>(tracks);if(shuffled)Collections.shuffle(selected);
        playFromHere(selected,selected.get(0).id);hideKeyboard();
    }
    private void shuffleQueue() {
        if(current()!=null&&!MusicClient.get(this).queue().isEmpty()){MusicClient.get(this).shuffle();if("Queue".equals(collection))main.postDelayed(this::query,300);toast("Playing Next shuffled");}
        else playSelection(true);
    }
    private void cycleRepeat() {
        String old=MusicClient.get(this).repeat();
        String value="Off".equals(old)?"All":"All".equals(old)?"One":"Off";
        MusicClient.get(this).setRepeat(value);renderPlayback();
        toast("Repeat "+value.toLowerCase(Locale.ROOT));
    }
    private static void space(LinearLayout parent,int value) {
        View spacer=new View(parent.getContext());
        parent.addView(spacer,new LinearLayout.LayoutParams(1,Math.round(value*parent.getResources().getDisplayMetrics().density)));
    }
    private void query() {
        if (adapter == null) { renderPlayback(); return; }
        boolean groupList=groupedCollection()&&!insideGroup();
        querying=true;
        playAll.setVisibility(groupList?View.GONE:View.VISIBLE);shuffle.setVisibility(groupList?View.GONE:View.VISIBLE);
        playAll.setEnabled(false);shuffle.setEnabled(false);
        sortButton.setVisibility(groupList||"Queue".equals(collection)?View.GONE:View.VISIBLE);
        sortButton.setText(sort + " ▾");
        sortButton.setContentDescription("Sort songs by " + sort + ". Change play order");
        collectionBack.setVisibility(insideGroup()?View.VISIBLE:View.GONE);
        collectionBack.setContentDescription("Back to "+collection);
        headerBack.setContentDescription(insideGroup()?"Back to "+collection:"Back to dashboard");
        String hint=groupList?"Search "+collection.toLowerCase(Locale.ROOT):insideGroup()?"Search these songs":"Search your library";
        searchField.setHint(hint);searchField.setContentDescription(hint);
        boolean songs="All".equals(collection)||"Stars".equals(collection)||"Recent".equals(collection);
        favouritesChip.setVisibility(songs&&!insideGroup()?View.VISIBLE:View.GONE);
        stylePill(favouritesChip,"Stars".equals(collection));
        recentChip.setVisibility(songs&&!insideGroup()?View.VISIBLE:View.GONE);
        stylePill(recentChip,"Recent".equals(collection));
        for(Map.Entry<String,TextView> entry:tabsByName.entrySet()){
            boolean chosen=entry.getKey().equals(collection)||("All".equals(entry.getKey())&&("Stars".equals(collection)||"Recent".equals(collection)));
            entry.getValue().setTextColor(chosen?palette.accent:palette.muted);
            entry.getValue().setTypeface(null,chosen?Typeface.BOLD:Typeface.NORMAL);
            entry.getValue().setBackground(chosen?LauncherTheme.rounded(this,(palette.accent&0x00ffffff)|0x18000000,12,0,0):LauncherTheme.dockItem(this,palette));
            entry.getValue().setSelected(chosen);
        }
        int generation=++queryGeneration;String term=search,order=sort,group=collection,key=browseKey,root=browseRoot;long selected=playlist;
        if(scanState!=null){
            boolean notice=library.scanning||library.scanStatus.startsWith("Scan ");
            scanState.setVisibility(notice?View.VISIBLE:View.GONE);scanState.setText(library.scanStatus);
        }
        MusicLibrary.WORK.execute(()->{
            List<MusicLibrary.Track> result;
            List<MusicLibrary.BrowseGroup> grouped=groupList?library.groups(group,term):Collections.emptyList();
            if(groupList)result=Collections.emptyList();
            else if("Queue".equals(group)) {
                ArrayList<MusicLibrary.Track> queued=new ArrayList<>();
                for(long queuedId:MusicClient.get(this).queue()){MusicLibrary.Track t=library.track(queuedId);if(t!=null&&(term.isEmpty()||(t.title+" "+t.artist+" "+t.folder+" "+t.name).toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))))queued.add(t);}
                
                result=queued;
            } else result=library.query(term,order,group,selected,key,root);
            String selectedName="Stars".equals(group)?"Favourites":"Recent".equals(group)?"Recently Played":"Queue".equals(group)?nowPlayingTitle:"Frequent".equals(group)?"Frequently Played":"Songs";
            if(groupList)selectedName=group;
            else if(key!=null)selectedName=key;
            if(selected>0)for(MusicLibrary.Playlist p:library.playlists())if(p.id==selected)selectedName=p.name;
            String heading=selectedName;
            main.post(()->{if(ActivityState.isGone(this)||generation!=queryGeneration)return;tracks.clear();tracks.addAll(result);
                groups.clear();groups.addAll(grouped);showingGroups=groupList;querying=false;adapter.notifyDataSetChanged();
                boolean albums=groupList&&"Albums".equals(group);int shown=groupList?grouped.size():result.size();
                if(albums)albumAdapter.notifyDataSetChanged();
                empty.setVisibility(shown==0?View.VISIBLE:View.GONE);
                list.setVisibility(shown>0&&!albums?View.VISIBLE:View.GONE);
                albumGrid.setVisibility(shown>0&&albums?View.VISIBLE:View.GONE);
                if(collectionLabel!=null)collectionLabel.setText(heading);
                if(count!=null)count.setText(groupList?grouped.size()+ ("Artists".equals(group)?(grouped.size()==1?" artist":" artists"):"Albums".equals(group)?(grouped.size()==1?" album":" albums"):(grouped.size()==1?" folder":" folders"))
                        :result.size()+ (result.size()==1?" song":" songs"));
                playAll.setEnabled(!result.isEmpty());shuffle.setEnabled(!result.isEmpty());
                empty.setText(term.trim().isEmpty()?(groupList?"No "+group+"\nChoose a music folder to get started.":"No Songs\nChoose a music folder to get started.")
                        :"No Results\nTry a different search.");
                if(scrollToCurrent&&!groupList){
                    scrollToCurrent=false;long now=MusicClient.get(this).currentId();
                    for(int i=0;i<result.size();i++)if(result.get(i).id==now){list.setSelectionFromTop(i,dp(48));break;}
                }
                renderPlayback();});
        });
    }
    private TextView soundChip(String tag,View.OnClickListener click){
        TextView chip=text("",11,palette.accent);chip.setSingleLine(true);medium(chip);
        chip.setGravity(Gravity.CENTER);chip.setPadding(dp(10),0,dp(10),0);chip.setLetterSpacing(.06f);
        chip.setTag(tag);chip.setOnClickListener(click);return chip;
    }
    private static final float[] BASS_STEPS={0,3,6,9};
    /** Bass is a slider in the equalizer; here one tap steps Off, +3, +6, +9 dB and back. */
    private void cycleBass(){
        EqualizerProfile profile=new EqualizerProfile(this);
        if(!profile.enabled()){startActivity(new Intent(this,EqualizerActivity.class));return;}
        int next=0;
        for(int i=0;i<BASS_STEPS.length;i++)if(profile.bass()>=BASS_STEPS[i]-.25f)next=(i+1)%BASS_STEPS.length;
        profile.setBass(BASS_STEPS[next]);
        if(!"Custom".equals(profile.preset()))getSharedPreferences("equalizer_profile",MODE_PRIVATE).edit().putString("preset","Custom").apply();
        soundChanged(profile);
    }
    private void toggleHeadroom(){
        EqualizerProfile profile=new EqualizerProfile(this);
        if(!profile.enabled()){startActivity(new Intent(this,EqualizerActivity.class));return;}
        profile.setHeadroom(!profile.headroom());
        soundChanged(profile);
    }
    private void soundChanged(EqualizerProfile profile){
        profile.flush();
        SoundEqualizerService.refresh(this);
        MusicClient.get(this).effectsChanged();
        renderSoundChips();
    }
    private void renderSoundChips(){
        if(bassChip==null||headroomChip==null)return;
        EqualizerProfile profile=new EqualizerProfile(this);
        boolean on=profile.enabled();
        String bass=!on?"BASS · EQ OFF":profile.bass()==0?"BASS OFF":String.format(java.util.Locale.US,"BASS +%.0f DB",profile.bass());
        updateText(bassChip,bass);
        bassChip.setContentDescription(!on?"Bass. The equalizer is off. Tap to open it.":profile.bass()==0?"Bass off. Tap to change.":"Bass plus "+Math.round(profile.bass())+" decibels. Tap to change.");
        updateText(headroomChip,!on?"HEADROOM · EQ OFF":profile.headroom()?"HEADROOM ON":"HEADROOM OFF");
        headroomChip.setContentDescription(!on?"Headroom. The equalizer is off. Tap to open it.":profile.headroom()?"Headroom on. Tap to turn off.":"Headroom off, loud tracks may clip. Tap to turn on.");
    }
    private void renderPlayback() {
        if(title==null)return;MusicClient service=MusicClient.get(this);MusicLibrary.Track t=current();
        updateText(title,t==null?"Not Playing":t.title);
        updateText(artist,service.error()!=null?service.error():t==null?"Choose a song from your library":artistLine(t));
        favorite.chosen(t!=null&&t.favorite);favorite.setEnabled(t!=null);favorite.setAlpha(t==null?.4f:1);
        favorite.setContentDescription(t!=null&&t.favorite?"Remove from Favourite Songs":"Add to Favourite Songs");
        if(detail!=null)updateText(detail,t==null?"LOCAL AUDIO":qualityLabel(t));
        if(crossfadeChip!=null){
            int fade=getSharedPreferences("music",0).getInt(MusicService.CROSSFADE,MusicService.CROSSFADE_DEFAULT);
            updateText(crossfadeChip,fade==0?"CROSSFADE OFF":"CROSSFADE "+fade+" S");
            crossfadeChip.setContentDescription(fade==0?"Crossfade off. Tap to change.":"Crossfade "+fade+" seconds. Tap to change.");
        }
        renderSoundChips();
        if(cover!=null)cover.bind(t);
        boolean playing=service.isPlaying();
        if(renderedTrack!=(t==null?-1:t.id)||renderedPlaying!=playing){renderedTrack=t==null?-1:t.id;renderedPlaying=playing;if(adapter!=null)adapter.notifyDataSetChanged();}
        if(cover!=null&&cover.hero){
            // The artwork settles back slightly while paused.
            float scale=playing||t==null?1f:.92f;
            if(cover.getTag(R.id.music_cover_scale)==null||(float)cover.getTag(R.id.music_cover_scale)!=scale){
                cover.setTag(R.id.music_cover_scale,scale);
                if(effects&&cover.isLaidOut())cover.animate().scaleX(scale).scaleY(scale).setDuration(420)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                else{cover.setScaleX(scale);cover.setScaleY(scale);}
            }
        }
        if(upNext!=null){
            MusicLibrary.Track following=service.upNextId()<0?null:cachedTrack(service.upNextId());
            updateText(upNext,following==null?"":"Up next · "+following.title+" · "+artistLine(following));
            upNext.setVisibility(following==null?View.INVISIBLE:View.VISIBLE);
        }
        play.icon(playing?MusicIconView.Icon.PAUSE:MusicIconView.Icon.PLAY);
        play.setContentDescription(playing?"Pause":"Play");
        String mode=service.repeat();
        if(repeat!=null){repeat.chosen(!"Off".equals(mode));repeat.badge("One".equals(mode)?"1":null);repeat.setContentDescription("Repeat "+mode.toLowerCase(Locale.ROOT));}
        renderProgress();
    }
    /** Seek bar and times only. The player reports changes, not the passing of time, so this also
     *  runs on each whole second while music plays and the screen is visible. */
    private void renderProgress(){
        main.removeCallbacks(progressTick);
        if(progress==null)return;MusicClient service=MusicClient.get(this);
        long duration=currentDuration();long position=service.position();
        progress.setEnabled(duration>0);if(!seeking){progress.setProgress(duration<=0?0:(int)(position*1000/duration));elapsed.setText(time(position));}remaining.setText(duration<=0?"—":"−"+time(Math.max(0,duration-position)));
        if(visible&&service.isPlaying())main.postDelayed(progressTick,1000-position%1000+20);
    }
    private MusicLibrary.Track current(){return cachedTrack(MusicClient.get(this).currentId());}
    /** The now-playing and up-next tracks: each playback update reads them, and they rarely change. */
    private final java.util.HashMap<Long,MusicLibrary.Track> trackCache=new java.util.HashMap<>();
    private MusicLibrary.Track cachedTrack(long id){
        MusicLibrary.Track track=trackCache.get(id);
        if(track==null){track=library.track(id);if(track!=null){if(trackCache.size()>=8)trackCache.clear();trackCache.put(id,track);}}
        return track;
    }
    /** Duration of the current song: the player's when known, else the library's. */
    private long currentDuration(){long d=MusicClient.get(this).duration();if(d>0)return d;MusicLibrary.Track t=current();return t==null?0:t.duration;}
    private static void updateText(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    private void starCurrent(){MusicLibrary.Track t=current();if(t!=null)toggleFavorite(t);}

    /**
     * The artwork is the biggest target on screen, so it does the two things wanted most while
     * driving: tap to favourite (with a star burst) and swipe sideways to change song.
     */
    private void installCoverGestures(CoverView view){
        android.view.GestureDetector detector=new android.view.GestureDetector(this,new android.view.GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(android.view.MotionEvent e){return true;}
            @Override public boolean onSingleTapUp(android.view.MotionEvent e){favouriteFromCover();return true;}
            @Override public boolean onFling(android.view.MotionEvent a,android.view.MotionEvent b,float vx,float vy){
                if(a==null)return false;float dx=b.getX()-a.getX(),dy=b.getY()-a.getY();
                if(Math.abs(dx)<dp(60)||Math.abs(dx)<Math.abs(dy)*1.5f)return false;
                command(dx<0?"next":"previous");view.nudge(dx<0?-1:1);return true;
            }
        });
        view.setOnTouchListener((v,e)->detector.onTouchEvent(e));
        view.setClickable(true);
        view.setOnClickListener(v->favouriteFromCover());
    }
    private void favouriteFromCover(){
        MusicLibrary.Track t=current();if(t==null){togglePlayback();return;}
        toggleFavorite(t);MusicLibrary.Track saved=library.track(t.id);
        boolean added=saved!=null&&saved.favorite;
        if(cover!=null)cover.burst(added);
        cover.announceForAccessibility(added?"Added to Favourites":"Removed from Favourites");
    }
    private void toggleFavorite(MusicLibrary.Track t){
        MusicLibrary.Track saved=library.track(t.id);if(saved==null)return;
        boolean selected=!saved.favorite;library.favorite(t.id,selected);trackCache.remove(t.id);
        for(MusicLibrary.Track row:tracks)if(row.id==t.id)row.favorite=selected;
        if(adapter!=null)adapter.notifyDataSetChanged();renderPlayback();
    }
    private void togglePlayback(){if(current()==null)playSelection(false);else command("toggle");}
    private void command(String action){MusicClient.get(this).command(action);}
    private void selectCollection(String name){if("Playlists".equals(name)){playlistMenu();return;}clearBrowse();setSearch("");collection=name;playlist=0;query();if(list!=null)list.setSelection(0);}
    private void sortMenu(){String[] values={"Artist","Folder","Title","Filename","Random","Most played"};dialog().setTitle("Play order").setItems(values,(d,i)->{sort=values[i];query();}).show();}
    private void compactOptions(){dialog().setTitle("Music").setItems(new String[]{"Now Playing","Playing Next","Frequently Played","New Playlist…","Equalizer","Crossfade…","Music Folders","Song Info"},(d,i)->{
        if(i==0){if(compact)showPlayer(true);}else if(i==1){clearBrowse();setSearch("");collection="Queue";playlist=0;if(compact&&expanded)showPlayer(false);else query();}
        else if(i==2){clearBrowse();setSearch("");collection="Frequent";playlist=0;if(compact&&expanded)showPlayer(false);else query();}
        else if(i==3)createPlaylist(null);else if(i==4)startActivity(new Intent(this,EqualizerActivity.class));else if(i==5)CrossfadeDialog.show(this,palette,null);else if(i==6)folderMenu();else{MusicLibrary.Track t=current();if(t!=null)songInfo(t);}
    }).show();}
    private void songInfo(MusicLibrary.Track t){dialog().setTitle(t.title).setMessage(t.artist+"\n"+t.album+"\n\n"+format(t)+" · "+time(t.duration)+"\n"+t.folder+" / "+t.name).setPositiveButton("Done",null).show();}
    private void trackMenu(MusicLibrary.Track t){ArrayList<String> options=new ArrayList<>(Arrays.asList("Add to Playlist…",t.favorite?"Remove from Favourite Songs":"Add to Favourite Songs","Song Info"));if(playlist>0)options.add("Remove from Playlist");dialog().setTitle(t.title).setItems(options.toArray(new String[0]),(d,i)->{if(i==0)addToPlaylist(t);else if(i==1)toggleFavorite(t);else if(i==2)songInfo(t);else library.remove(playlist,t.id);}).show();}
    private void playlistMenu(){
        List<MusicLibrary.Playlist> entries=library.playlists();
        ArrayList<String> names=new ArrayList<>();names.add("New Playlist…");names.add("Favourite Songs");
        for(MusicLibrary.Playlist p:entries)names.add(p.name);names.add("Manage Playlists");
        dialog().setTitle("Playlists").setItems(names.toArray(new String[0]),(d,i)->{
            if(i==0)createPlaylist(null);else if(i==1)selectCollection("Stars");
            else if(i<entries.size()+2){clearBrowse();setSearch("");playlist=entries.get(i-2).id;collection="Playlists";query();}
            else managePlaylists(entries);
        }).show();
    }
    private void addToPlaylist(MusicLibrary.Track track){List<MusicLibrary.Playlist> entries=library.playlists();String[] names=new String[entries.size()+1];names[0]="New Playlist…";for(int i=0;i<entries.size();i++)names[i+1]=entries.get(i).name;dialog().setTitle("Add to Playlist").setItems(names,(d,i)->{if(i==0)createPlaylist(track);else{library.add(entries.get(i-1).id,track.id);toast("Added to "+entries.get(i-1).name);}}).show();}
    private void createPlaylist(MusicLibrary.Track track){EditText input=new EditText(this);input.setSingleLine(true);input.setTextColor(palette.ink);input.setHintTextColor(palette.muted);input.setHint("Playlist name");input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});AlertDialog d=dialog().setTitle("New playlist").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Create",null).create();d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{long id=library.createPlaylist(input.getText().toString());if(track!=null)library.add(id,track.id);toast(track==null?"Playlist created":"Playlist created · track added");d.dismiss();}catch(IllegalArgumentException e){input.setError(e.getMessage());}}));d.show();ThemedDialog.match(d,palette);}
    private void managePlaylists(List<MusicLibrary.Playlist> entries){if(entries.isEmpty()){toast("No playlists yet");return;}String[] names=new String[entries.size()];for(int i=0;i<names.length;i++)names[i]=entries.get(i).name;dialog().setTitle("Delete a playlist · music files stay untouched").setItems(names,(d,i)->dialog().setTitle("Delete “"+entries.get(i).name+"”?").setMessage("Only the playlist is removed. Your songs and favourites remain.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(x,w)->{long id=entries.get(i).id;library.deletePlaylist(id);if(playlist==id){playlist=0;collection="All";}query();}).show()).show();}
    private void folderMenu(){
        String tree=getSharedPreferences("music",0).getString("tree",null);
        String[] actions=tree==null?new String[]{"Select music folder","Decoder & open-source licenses"}:new String[]{"Select another folder","Rescan selected folder","Decoder & open-source licenses"};
        dialog().setTitle("Music folders").setItems(actions,(d,i)->{
            if(i==actions.length-1){decoderInfo();return;}
            if(library.scanning){toast("A scan is already running");return;}
            if(i==1)MusicScanService.start(this,Uri.parse(tree));else chooseFolder();
        }).show();
    }
    private void decoderInfo(){
        dialog().setTitle("Powered by AndroidX Media3")
                .setMessage("Playback by Media3 ExoPlayer (Apache License 2.0) using this box's built-in decoders: FLAC, MP3, AAC (.m4a), Ogg Vorbis, Opus, WAV and Matroska audio. Music files are read locally. Missing album covers can be looked up online (iTunes Search) and are kept on this box.")
                .setNegativeButton("Close",null).setNeutralButton("Source",(d,i)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/androidx/media")));}catch(ActivityNotFoundException e){toast("No browser installed");}})
                .show();
    }
    private void chooseFolder(){try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),FOLDER);}catch(ActivityNotFoundException e){toast("This firmware has no folder picker");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==FOLDER&&result==RESULT_OK&&data!=null&&data.getData()!=null){try{getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);android.content.SharedPreferences music=getSharedPreferences("music",0);String picked=data.getData().toString(),previous=music.getString("tree",null);android.content.SharedPreferences.Editor e=music.edit().putString("tree",picked);if(previous!=null&&!previous.equals(picked))e.putString("move_from",previous);e.apply();MusicScanService.start(this,data.getData());}catch(SecurityException e){toast("Folder access was not granted · select it again");}}}
    private void hideKeyboard(){((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(),0);}
    private AlertDialog.Builder dialog(){return new ThemedDialog(this,palette);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView button(String value,String description,Runnable action){TextView v=text(value,14,palette.ink);v.setGravity(Gravity.CENTER);v.setSingleLine(true);v.setEllipsize(TextUtils.TruncateAt.END);v.setContentDescription(description);v.setBackground(LauncherTheme.dockItem(this,palette));v.setOnClickListener(x->action.run());v.setFocusable(true);v.setMinHeight(dp(48));return v;}
    private LinearLayout.LayoutParams lp(int width,int height){return new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    static String time(long ms){long seconds=Math.max(0,ms)/1000;return String.format(Locale.US,"%d:%02d",seconds/60,seconds%60);}
    private static String format(MusicLibrary.Track t) {
        int dot=t.name.lastIndexOf('.');
        return dot<0?"AUDIO":t.name.substring(dot+1).toUpperCase(Locale.ROOT);
    }
    private final class TrackAdapter extends BaseAdapter {
        @Override public int getCount(){return showingGroups?groups.size():tracks.size();}
        @Override public Object getItem(int i){return showingGroups?groups.get(i):tracks.get(i);}
        @Override public long getItemId(int i){return showingGroups?-groups.get(i).artwork.id:tracks.get(i).id;}
        @Override public int getViewTypeCount(){return 2;}
        @Override public int getItemViewType(int i){return showingGroups?1:0;}
        @Override public boolean hasStableIds(){return true;}
        @Override public View getView(int position,View recycled,ViewGroup parent) {
            if(showingGroups)return groupView(groups.get(position),recycled);
            LinearLayout root;
            if(recycled==null){
                root=row();root.setPadding(dp(8),dp(8),dp(4),dp(8));root.setMinimumHeight(dp(72));
                FrameLayout artBox=new FrameLayout(MusicActivity.this);
                artBox.addView(new CoverView(false),new FrameLayout.LayoutParams(-1,-1));
                artBox.addView(new PlayingGlyph(),new FrameLayout.LayoutParams(-1,-1));
                root.addView(artBox,lp(52,52));
                LinearLayout names=column();names.setGravity(Gravity.CENTER_VERTICAL);
                names.setPadding(dp(14),0,dp(8),0);
                TextView song=text("",16,palette.ink);medium(song);
                song.setSingleLine(true);song.setEllipsize(TextUtils.TruncateAt.END);
                TextView subtitle=text("",12,palette.muted);
                subtitle.setSingleLine(true);subtitle.setEllipsize(TextUtils.TruncateAt.END);
                names.addView(song);space(names,5);names.addView(subtitle);
                root.addView(names,new LinearLayout.LayoutParams(0,-1,1));
                TextView duration=text("",13,palette.muted);duration.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
                duration.setFontFeatureSettings("tnum");root.addView(duration,lp(56,48));
                MusicIconView star=icon(MusicIconView.Icon.STAR,"Favourite",()->{});
                // A focusable child stops ListView from delivering row taps, so songs would not play.
                star.setFocusable(false);
                root.addView(star,lp(48,52));
            }else root=(LinearLayout)recycled;
            MusicLibrary.Track t=tracks.get(position);
            boolean playingRow=t.id==renderedTrack;
            root.setTag("track_"+t.id);
            root.setBackground(playingRow?LauncherTheme.rounded(MusicActivity.this,(palette.accent&0x00ffffff)|(palette.light?0x1a000000:0x24000000),14,0,0):null);
            FrameLayout artBox=(FrameLayout)root.getChildAt(0);
            ((CoverView)artBox.getChildAt(0)).bind(t);
            ((PlayingGlyph)artBox.getChildAt(1)).active(playingRow);
            LinearLayout names=(LinearLayout)root.getChildAt(1);
            TextView song=(TextView)names.getChildAt(0);
            song.setText(t.title);song.setTextColor(playingRow?palette.accent:palette.ink);
            ((TextView)names.getChildAt(2)).setText("Folder".equals(sort)?t.folder:artistLine(t));
            SpannableStringBuilder length=new SpannableStringBuilder(t.duration>0?time(t.duration):"—");
            int from=length.length();length.append("\n").append(format(t));
            length.setSpan(new android.text.style.RelativeSizeSpan(.7f),from,length.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ((TextView)root.getChildAt(2)).setText(length);
            MusicIconView star=(MusicIconView)root.getChildAt(3);
            star.setTag("track_favorite_"+t.id);star.chosen(t.favorite);
            // Every song exposes the same action; a quiet outline becomes a filled favourite.
            star.setVisibility(View.VISIBLE);
            star.setAlpha(t.favorite || playingRow ? 1f : .65f);
            star.setContentDescription((t.favorite?"Remove ":"Add ")+t.title+(t.favorite?" from Favourite Songs":" to Favourite Songs"));
            star.setOnClickListener(v->toggleFavorite(t));
            return root;
        }
        private View groupView(MusicLibrary.BrowseGroup group,View recycled){
            LinearLayout root;
            if(recycled==null){
                root=row();root.setPadding(dp(2),dp(10),0,dp(10));root.setMinimumHeight(dp(80));
                root.addView(new CoverView(false),lp(52,52));
                LinearLayout names=column();names.setGravity(Gravity.CENTER_VERTICAL);names.setPadding(dp(14),0,dp(8),0);
                TextView name=text("",19,palette.ink);name.setTypeface(null,Typeface.BOLD);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);
                TextView summary=text("",12,palette.muted);summary.setSingleLine(true);summary.setEllipsize(TextUtils.TruncateAt.END);
                names.addView(name);space(names,5);names.addView(summary);
                root.addView(names,new LinearLayout.LayoutParams(0,-1,1));
                MusicIconView next=icon(MusicIconView.Icon.BACK,"",()->{});next.setRotation(180);
                next.setClickable(false);next.setFocusable(false);next.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                root.addView(next,lp(48,52));
            }else root=(LinearLayout)recycled;
            root.setTag("music_group_"+group.artwork.id);
            ((CoverView)root.getChildAt(0)).bind(group.artwork,group.key);
            LinearLayout names=(LinearLayout)root.getChildAt(1);
            ((TextView)names.getChildAt(0)).setText(group.key);
            String summary=group.songs+(group.songs==1?" song":" songs");
            if("Artists".equals(collection)&&group.albums>0)summary+=" · "+group.albums+(group.albums==1?" album":" albums");
            else if("Folders".equals(collection))summary+=" · "+rootName(group.root);
            ((TextView)names.getChildAt(2)).setText(summary);
            root.setContentDescription(group.key+", "+summary+". Open songs");
            return root;
        }
    }
    private static String rootName(String root){
        try{String name=android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(root));return name.substring(name.lastIndexOf('/')+1);}
        catch(RuntimeException e){return "Music folder";}
    }

    private final class AlbumAdapter extends BaseAdapter {
        @Override public int getCount(){return showingGroups&&"Albums".equals(collection)?groups.size():0;}
        @Override public Object getItem(int i){return groups.get(i);}
        @Override public long getItemId(int i){return -groups.get(i).artwork.id;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            LinearLayout tile;
            if(recycled==null){
                tile=column();tile.setPadding(dp(4),dp(4),dp(4),dp(6));
                tile.addView(new CoverView(false),lp(-1,150));
                TextView name=text("",14,palette.ink);medium(name);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);
                name.setPadding(dp(2),dp(10),dp(2),0);tile.addView(name);
                TextView by=text("",12,palette.muted);by.setSingleLine(true);by.setEllipsize(TextUtils.TruncateAt.END);
                by.setPadding(dp(2),dp(4),dp(2),0);tile.addView(by);
            }else tile=(LinearLayout)recycled;
            MusicLibrary.BrowseGroup group=groups.get(position);
            tile.setTag("music_album_"+group.artwork.id);
            ((CoverView)tile.getChildAt(0)).bind(group.artwork,group.key);
            ((TextView)tile.getChildAt(1)).setText(group.key);
            String by=artistLine(group.artwork);int dot=by.indexOf(" · ");if(dot>0)by=by.substring(0,dot);
            ((TextView)tile.getChildAt(2)).setText(by+" · "+group.songs+(group.songs==1?" song":" songs"));
            tile.setContentDescription(group.key+", "+by+". Open album");
            return tile;
        }
    }

    /** Small animated bars over the playing song's artwork; still while paused. */
    private final class PlayingGlyph extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean active;
        PlayingGlyph(){super(MusicActivity.this);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void active(boolean value){if(active!=value){active=value;}invalidate();}
        @Override protected void onDraw(Canvas canvas){
            if(!active)return;
            float w=getWidth(),h=getHeight();
            paint.setColor(0x8c000000);canvas.drawRoundRect(0,0,w,h,dp(8),dp(8),paint);
            float bar=w*.11f,gap=w*.08f,left=(w-bar*3-gap*2)/2,base=h*.74f,tall=h*.46f;
            paint.setColor(Color.WHITE);
            // Still bars: every redraw is captured and encoded for CarPlay, so nothing animates while music plays.
            for(int i=0;i<3;i++){
                float level=i==1?.8f:i==0?.5f:.62f;
                float x=left+i*(bar+gap);canvas.drawRoundRect(x,base-tall*level,x+bar,base,bar/2,bar/2,paint);
            }
        }
    }

    private final class CoverView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF box=new RectF();
        private final Path clipping=new Path();
        private final Matrix matrix=new Matrix();
        final boolean hero;
        private String key;
        private Bitmap bitmap;
        private BitmapShader image;
        private Shader placeholder;
        private String initials="♫";
        private float burst;private boolean burstAdded;
        private final Paint burstPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        /** A star that swells and fades over the artwork (about 18 frames at 30 fps). */
        void burst(boolean added){
            burstAdded=added;
            android.animation.ValueAnimator a=android.animation.ValueAnimator.ofFloat(0f,1f).setDuration(600);
            a.addUpdateListener(x->{burst=(float)x.getAnimatedValue();invalidate();});
            a.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator x){burst=0;invalidate();}});
            a.start();
        }
        /** A short slide in the swipe direction, so the gesture feels answered. */
        void nudge(int direction){
            if(!effects)return;
            animate().translationX(direction*dp(36)).alpha(.6f).setDuration(120).withEndAction(()->
                    animate().translationX(0).alpha(1f).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start()).start();
        }
        CoverView(boolean hero){
            super(MusicActivity.this);this.hero=hero;
            setContentDescription("Album artwork");
            // Drawn once into a GPU layer; other views redrawing (progress, playing glyph) reuse it.
            setLayerType(LAYER_TYPE_HARDWARE,null);
            if(hero){
                // A real shadow lifts the artwork off the panel.
                setOutlineProvider(new ViewOutlineProvider(){
                    @Override public void getOutline(View view,Outline outline){outline.setRoundRect(Math.round(box.left),Math.round(box.top),Math.round(box.right),Math.round(box.bottom),dp(16));outline.setAlpha(.45f);}
                });
                setElevation(dp(14));
            }
        }
        void bind(MusicLibrary.Track track){bind(track,null);}
        void bind(MusicLibrary.Track track,String groupName){
            String next=(track==null?"empty":track.uri+"|"+track.art)+"|"+groupName;
            if(next.equals(key))return;
            key=next;bitmap=null;image=null;
            if(hero&&track==null)applyNowArt(null);
            String name=groupName!=null?groupName:track==null?"":(track.album.isEmpty()?track.title:track.album).trim();
            initials=name.isEmpty()?"♫":name.substring(0,1).toUpperCase(Locale.ROOT);
            setContentDescription(groupName!=null?"Artwork for "+groupName:track==null?"No album artwork":"Album artwork for "+(track.album.isEmpty()?track.title:track.album));
            invalidate();
            if(track!=null)MusicArt.load(MusicActivity.this,track,value->{
                if(next.equals(key)&&!ActivityState.isGone(MusicActivity.this)){bitmap=value;image=value==null?null:new BitmapShader(value,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);invalidate();
                    if(hero)applyNowArt(value);}
            });
        }
        private float corner;
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            super.onSizeChanged(w,h,ow,oh);
            float side=Math.max(0,Math.min(w,h-(hero?dp(8):0)));
            box.set((w-side)/2,(h-side)/2,(w+side)/2,(h+side)/2);
            float radius=hero?dp(16):Math.max(dp(8),side*.07f);
            corner=radius;
            clipping.reset();clipping.addRoundRect(box,radius,radius,Path.Direction.CW);
            if(hero)invalidateOutline();
            placeholder=new LinearGradient(box.left,box.top,box.right,box.bottom,
                    new int[]{0xff526d87,0xff2c4057,0xff171f32},null,Shader.TileMode.CLAMP);
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);if(box.width()<=0)return;
            paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setColor(palette.light?0x14000000:0x50000000);
            paint.setColor(Color.WHITE);
            if(image!=null&&bitmap!=null){
                // Rounded corners straight from the image shader: no clip path to re-rasterise.
                float scale=Math.max(box.width()/bitmap.getWidth(),box.height()/bitmap.getHeight());
                matrix.reset();matrix.setScale(scale,scale);
                matrix.postTranslate(box.centerX()-bitmap.getWidth()*scale/2,box.centerY()-bitmap.getHeight()*scale/2);
                image.setLocalMatrix(matrix);paint.setShader(image);canvas.drawRoundRect(box,corner,corner,paint);
                paint.setShader(null);
            }else{
                canvas.save();canvas.clipPath(clipping);
                paint.setShader(placeholder);canvas.drawRect(box,paint);paint.setShader(null);
                paint.setColor(0x16ffffff);canvas.drawCircle(box.right,box.top,box.width()*.8f,paint);
                canvas.drawCircle(box.left,box.bottom,box.width()*.65f,paint);
                paint.setColor(0xffe0e8ee);paint.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
                paint.setTextSize(box.width()*.35f);paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(initials,box.centerX(),box.centerY()-(paint.ascent()+paint.descent())/2,paint);
                paint.setShader(null);canvas.restore();
            }
            if(burst>0&&burst<1){
                float size=box.width()*(.25f+.25f*burst);
                burstPaint.setTextAlign(Paint.Align.CENTER);burstPaint.setTextSize(size);
                burstPaint.setColor(burstAdded?0xffffc845:0xffffffff);
                burstPaint.setAlpha(Math.round(255*(burst<.3f?burst/.3f:(1-burst)/.7f)));
                burstPaint.setShadowLayer(dp(8),0,dp(2),0x66000000);
                canvas.drawText(burstAdded?"★":"☆",box.centerX(),box.centerY()-(burstPaint.ascent()+burstPaint.descent())/2,burstPaint);
            }
        }
    }
}
