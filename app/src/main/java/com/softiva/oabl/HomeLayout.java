package com.softiva.oabl;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Home's one arrangement, fitted to the window without page scrolling: a large navigation card on
 * the left (Google Maps floats over its bottom-left corner as picture-in-picture while navigating,
 * so the guidance sits to the right of that corner) and a bar on the right with battery, the
 * drive's energy facts, speed and media.
 */
final class HomeLayout {
    private final Activity host;
    private final float density;
    private final LinearLayout panel, energyColumn, contextColumn, compactRow, mediaColumn, dock;
    private final View trip, energy, telemetry, weather, navigation, topBar;
    private boolean driving, guiding, applying, promoted, narrow, shortWindow, veryShort;
    private HomeDashboardMode mode = HomeDashboardMode.NORMAL;
    /** False while the car has never reported a level: the battery card steps aside rather than show dashes. */
    private boolean energyShown = true;
    void energyShown(boolean shown) {
        if (shown == energyShown) return;
        energyShown = shown;
        energy.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    HomeLayout(Activity host) {
        this.host = host;
        density = host.getResources().getDisplayMetrics().density;
        panel = host.findViewById(R.id.dashboard_panel);
        energyColumn = host.findViewById(R.id.instrument_strip);
        contextColumn = host.findViewById(R.id.main_row);
        compactRow = host.findViewById(R.id.calendar_column);
        mediaColumn = host.findViewById(R.id.right_column);
        trip = host.findViewById(R.id.trip_card);
        energy = host.findViewById(R.id.energy_card);
        telemetry = host.findViewById(R.id.telemetry_card);
        weather = host.findViewById(R.id.calendar_card);
        navigation = host.findViewById(R.id.navigation_hint_card);
        topBar = host.findViewById(R.id.top_bar);
        dock = host.findViewById(R.id.dock);
        host.findViewById(R.id.stage).addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (r-l != or-ol || b-t != ob-ot) apply(driving,guiding,mode);
        });
    }

    private float typeScale = 1f;
    /** Smallest label the driver should ever have to read, in sp; relaxed in a tight window. */
    private float minLabelSp = 13f;
    private int dp(float value) { return Math.round(value*density); }
    boolean narrow() { return narrow; }
    boolean promoted() { return promoted; }
    boolean shortWindow() { return shortWindow; }
    boolean veryShort() { return veryShort; }
    HomeDashboardMode mode() { return mode; }

    void apply(boolean driving, boolean guiding) { apply(driving,guiding,mode); }

    void apply(boolean driving, boolean guiding, HomeDashboardMode mode) {
        if (applying) return;
        applying = true;
        try {
            this.driving = driving; this.guiding = guiding;
            this.mode = mode == null ? HomeDashboardMode.NORMAL : mode;
            int width = host.getResources().getConfiguration().screenWidthDp;
            int height = host.getResources().getConfiguration().screenHeightDp;
            // Grow type and the hero numbers on taller screens so the cards fill the space and the
            // labels stay legible from the driver's seat, with a floor so nothing is ever tiny.
            typeScale = Math.max(1f, Math.min(1.3f, height / 600f));
            narrow = width < 900; shortWindow = height <= 640; promoted = guiding;
            boolean compact = narrow || shortWindow;
            veryShort = height < 520;
            // A tight window cannot afford the full floor; a roomy one should read large.
            minLabelSp = 13f;
            int gap = compact ? 8 : 10;
            boolean driverLeft = "left".equals(new LauncherPreferences(host).driverSide());
            arrange();

            LinearLayout home = host.findViewById(R.id.home_content);
            home.setPadding(dp(compact?10:14),dp(veryShort?6:compact?8:10),dp(compact?10:14),dp(4));
            height(topBar,veryShort?48:compact?64:Math.max(89,pipHeader()));
            text(R.id.clock,veryShort?40:compact?52:75);
            text(R.id.date,compact?14:17);
            text(R.id.weather_status,compact?13:15);
            host.findViewById(R.id.vehicle_name).setVisibility(compact?View.GONE:View.VISIBLE);
            host.findViewById(R.id.header_space).setVisibility(narrow?View.GONE:View.VISIBLE);
            text(R.id.network_status,compact?13:15);
            ((TextView)host.findViewById(R.id.network_status)).setMaxWidth(dp(narrow?270:560));

            // Battery and the drive verdict take most of the width. The speed column follows the driver.
            View first = contextColumn; // the navigation column, first in the layout file
            if (panel.getChildAt(0) != first) { detach(first); panel.addView(first,0); }
            size(energyColumn,0,-1,narrow?1.45f:1.55f,driverLeft?0:gap,0);
            size(contextColumn,0,-1,1,driverLeft?gap:0,0);
            margins(contextColumn,0,0);
            size(energy,-1,0,shortWindow?1.25f:1.2f,0,0);
            size(trip,-1,0,1,0,0); margins(trip,0,gap);
            pad(energy,narrow?16:shortWindow?18:22,shortWindow?10:16,narrow?16:shortWindow?18:22,shortWindow?9:14);
            pad(trip,narrow?16:shortWindow?18:22,veryShort?5:shortWindow?8:14,narrow?16:shortWindow?18:22,veryShort?5:shortWindow?8:12);
            // The battery % and range fill whatever height the card has (auto-sized to both width
            // and height, so they grow on a big screen and can never overflow a small one).
            // With the drive card gone the battery card has the whole column, so the hero may grow further.
            fit(R.id.energy_soc,compact?36:44,compact?130:240);
            fit(R.id.energy_range,compact?22:28,compact?88:170);
            text(R.id.energy_soc_note,compact?12:15);
            text(R.id.energy_range_note,compact?12:15);
            text(R.id.energy_reference_row,compact?12:14);
            text(R.id.energy_result,compact?12:14);
            text(R.id.energy_benchmark,compact?11:13);
            for(int id:new int[]{R.id.energy_usable,R.id.energy_reserve,R.id.energy_12v,R.id.energy_odometer,R.id.energy_economy,R.id.energy_regen,R.id.energy_cost}) text(id,13);
            width(host.findViewById(R.id.energy_battery_icon),veryShort?28:narrow?34:shortWindow?40:58);
            height(host.findViewById(R.id.energy_battery_icon),veryShort?36:narrow?48:shortWindow?54:80);
            margins(host.findViewById(R.id.energy_range_column),narrow?10:22,0);
            size(host.findViewById(R.id.energy_primary_row),-1,0,1,0,0);
            // The charge gauge sits under the hero; MainActivity shows it whenever a level is known.
            height(host.findViewById(R.id.energy_bar),compact?12:16);
            ((EnergyBarView)host.findViewById(R.id.energy_bar)).setThickness(compact?10:14);
            margins(host.findViewById(R.id.energy_bar),0,compact?8:12);
            ((TextView)host.findViewById(R.id.energy_result)).setMaxLines(1);
            host.findViewById(R.id.energy_result).setVisibility(veryShort?View.GONE:View.VISIBLE);
            ((TextView)host.findViewById(R.id.energy_reference_row)).setMaxLines(2);

            fit(R.id.trip_verdict,compact?22:28,shortWindow?28:narrow?32:44);
            text(R.id.trip_performance,shortWindow?14:narrow?13:16);
            for(int id:new int[]{R.id.trip_consumption,R.id.trip_energy,R.id.trip_regen,R.id.trip_cost}) text(id,13);
            ((TextView)host.findViewById(R.id.trip_performance)).setMaxLines(2);
            host.findViewById(R.id.trip_performance).setVisibility(veryShort?View.GONE:View.VISIBLE);
            text(R.id.trip_note,compact?12:14);
            ((TextView)host.findViewById(R.id.trip_note)).setMaxLines(narrow?2:1);
            host.findViewById(R.id.trip_note).setVisibility(veryShort?View.GONE:View.VISIBLE);
            for (int id:new int[]{R.id.trip_distance,R.id.trip_time,R.id.trip_average,R.id.trip_peak}) fit(id,compact?18:22,compact?44:64);
            for (int id:new int[]{R.id.trip_distance_label,R.id.trip_time_label,R.id.trip_average_label,R.id.trip_peak_label}) text(id,compact?11:13);
            margins(host.findViewById(R.id.trip_verdict),0,shortWindow?4:8);
            margins(host.findViewById(R.id.trip_metrics),0,shortWindow?4:8);
            size(host.findViewById(R.id.trip_metrics),-1,0,1,0,0);

            // The right column retains speed, navigation, weather and tactile media controls in every mode.
            size(telemetry,-1,shortWindow?(veryShort?112:124):narrow?150:Math.round((driving?200:166)*typeScale),0,0,0); margins(telemetry,0,0);
            size(navigation,-1,0,1,0,0); margins(navigation,0,gap);
            // Stack weather over the media controls only in a narrow window that is also tall
            // (a portrait-ish split). When the window is short there is no height to stack, so the
            // media card is kept beside weather with enough row height for its transport buttons;
            // a tiny stacked row used to clip the controls off the bottom.
            boolean stackRow = narrow && host.getResources().getConfiguration().screenHeightDp > 560;
            size(compactRow,-1,stackRow?206:shortWindow?(veryShort?116:136):Math.round(150*typeScale),0,0,0); margins(compactRow,0,gap);
            compactRow.setOrientation(stackRow?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
            if (stackRow) {
                size(weather,-1,68,0,0,0); margins(weather,0,0);
                size(mediaColumn,-1,0,1,0,0); margins(mediaColumn,0,gap);
            } else {
                size(weather,0,-1,narrow?.8f:.85f,gap,0); margins(weather,0,0);
                size(mediaColumn,0,-1,narrow?1.35f:1.25f,0,0); margins(mediaColumn,0,0);
            }
            pad(telemetry,compact?14:20,compact?9:12,compact?14:18,compact?8:10);
            text(R.id.speed_value,veryShort?56:shortWindow?66:narrow?84:104);
            text(R.id.speed_unit,compact?14:18);
            text(R.id.current_road,compact?12:15);
            text(R.id.telemetry_label,compact?10:11);
            text(R.id.speed_scale,compact?9:11);
            text(R.id.launcher_tagline,compact?8:10);
            // Weather's hourly outlook remains available on tap; its Home tile shows current conditions.
            for (int id:new int[]{R.id.weather_hours,R.id.weather_sun,R.id.weather_updated}) host.findViewById(id).setVisibility(View.GONE);
            ((LinearLayout)weather).setGravity(Gravity.CENTER_VERTICAL);
            pad(weather,compact?10:14,narrow?5:8,compact?10:12,narrow?4:8);
            text(R.id.weather_label,compact?9:10);
            text(R.id.weather_temp,narrow?26:36);
            text(R.id.weather_condition,compact?12:15);
            text(R.id.weather_range,compact?12:14);
            height(host.findViewById(R.id.weather_icon_host),narrow?26:34);
            width(host.findViewById(R.id.weather_icon_host),narrow?26:34);
            ((TextView)host.findViewById(R.id.weather_condition)).setMaxLines(narrow?1:2);

            pad(navigation,compact?12:18,shortWindow?5:8,compact?12:16,shortWindow?5:8);
            text(R.id.navigation_where,compact?22:30);
            text(R.id.navigation_hint_title,compact?21:24);
            text(R.id.navigation_hint_detail,compact?12:13);
            ((TextView)host.findViewById(R.id.navigation_hint_detail)).setMaxLines(shortWindow?1:2);
            text(R.id.navigation_hint_label,11);
            text(R.id.navigation_eta,16);
            text(R.id.navigation_hint_trip,11);
            text(R.id.navigation_arrival_soc,13);
            text(R.id.navigation_end,14);
            ((TextView)host.findViewById(R.id.navigation_hint_title)).setMaxLines(1);
            // In a short window the maneuver is the navigation heading, freeing a full text row.
            View navHeader=(View)host.findViewById(R.id.navigation_hint_label).getParent();
            navHeader.setVisibility(shortWindow&&guiding?View.GONE:View.VISIBLE);
            View navFooter=(View)host.findViewById(R.id.navigation_end).getParent();
            height(navFooter,shortWindow?22:28); margins(navFooter,0,shortWindow?2:4);
            height(host.findViewById(R.id.navigation_end),shortWindow?22:28);
            height(host.findViewById(R.id.navigation_turn_progress),shortWindow?3:4);
            margins(host.findViewById(R.id.navigation_turn_progress),0,shortWindow?4:8);
            margins((View)host.findViewById(R.id.navigation_chips).getParent(),0,shortWindow?3:6);
            width(host.findViewById(R.id.navigation_hint_icon),compact?36:44);
            height(host.findViewById(R.id.navigation_hint_icon),compact?36:44);

            pad(host.findViewById(R.id.media_content),compact?10:12,shortWindow?6:8,compact?10:12,shortWindow?10:12);
            LinearLayout mediaInfo = host.findViewById(R.id.media_info_row);
            mediaInfo.setOrientation(LinearLayout.HORIZONTAL);
            mediaInfo.setGravity(Gravity.CENTER_VERTICAL);
            size(host.findViewById(R.id.media_text),0,-2,1,0,0);
            margins(host.findViewById(R.id.media_text),8,0);
            // The song is a quiet line: small cover, small title. The transport row is the hero, so the
            // buttons are hit blind, each taking a quarter of the card's width.
            fixed(R.id.media_title,veryShort?14:15);
            fixed(R.id.media_subtitle,13);
            fixed(R.id.media_section_label,13); fixed(R.id.media_source,13);
            ((TextView)host.findViewById(R.id.media_title)).setMaxLines(1);
            width(host.findViewById(R.id.media_artwork),veryShort?28:shortWindow?32:36);
            height(host.findViewById(R.id.media_artwork),veryShort?28:shortWindow?32:36);
            host.findViewById(R.id.media_header).setVisibility(View.GONE);
            for (int id:new int[]{R.id.media_favorite,R.id.media_eq,R.id.media_format}) host.findViewById(id).setVisibility(View.GONE);
            int button=veryShort?46:shortWindow?54:narrow?62:Math.min(80,Math.round(68*typeScale));
            height(host.findViewById(R.id.media_controls),button);
            for (int id:new int[]{R.id.media_previous,R.id.media_play_pause,R.id.media_next,R.id.media_playlists,R.id.media_expand,R.id.media_favorite,R.id.media_eq}) {
                size(host.findViewById(id),0,button,1,0,0);
                int p=dp(Math.round(button*.27f)); host.findViewById(id).setPadding(p,p,p,p);
            }
            host.findViewById(R.id.media_progress).setVisibility(View.GONE);
            host.findViewById(R.id.media_open).setVisibility(View.GONE);
            host.findViewById(R.id.media_controls_spacer).setVisibility(View.GONE);
            height(host.findViewById(R.id.media_progress),0);
            margins(host.findViewById(R.id.media_controls),0,0);
            ((TextView)host.findViewById(R.id.media_subtitle)).setMaxLines(1);
            ((TextView)host.findViewById(R.id.media_source)).setMaxWidth(dp(120));
            mapLayout(gap,driverLeft);
            // Fit speed to its measured viewport as well as width; fixed sp clipped at 579 dp.
            TextView speedText=host.findViewById(R.id.speed_value);
            // The unit sits right against the number, so the width follows the digits.
            size(speedText,-2,-1,0,0,0); speedText.setGravity(Gravity.CENTER_VERTICAL);
            // A set size from the card's height: auto-sizing can't follow a width that follows the digits, and
            // once the column narrowed for a route it left the number too thin to draw.
            fixed(R.id.speed_value,Math.max(32,(shortWindow?(veryShort?96:108):Math.round((driving?136:120)*typeScale))-62));
            // The dock's height sets the cards' bottom edge, which has to clear the PiP tile's (dp-fixed) bottom on any screen.
            height(dock,veryShort?60:64); margins(dock,0,4);
            margins(host.findViewById(R.id.stage),0,veryShort?4:compact?8:10);
        } finally { applying=false; }
    }

    /** True once weather is on and has arrived, so the header line replaces the card. */
    static boolean weatherInHeader(Activity host) {
        return new LauncherPreferences(host).weatherEnabled() && DashboardData.get(host).forecast != null;
    }

    /** Keep every view attached, including hidden energy tools, so renderers and listeners survive. */
    private void arrange() {
        energyColumn.setOrientation(LinearLayout.VERTICAL);
        contextColumn.setOrientation(LinearLayout.VERTICAL);
        // One layout: battery, speed and media stacked in the widget column; the other column is the
        // navigation card (which Maps' picture-in-picture window overlaps). Weather and the retired
        // drive card stay attached but hidden.
        place(energyColumn,0,energy); place(energyColumn,1,telemetry); place(energyColumn,2,mediaColumn);
        place(contextColumn,0,navigation); place(contextColumn,1,compactRow);
        place(compactRow,0,weather); place(compactRow,1,trip);
        energy.setVisibility(energyShown ? View.VISIBLE : View.GONE); telemetry.setVisibility(View.VISIBLE); mediaColumn.setVisibility(View.VISIBLE);
        navigation.setVisibility(View.VISIBLE);
        for (View hidden:new View[]{compactRow,weather,trip}) hidden.setVisibility(View.GONE);
    }

    /** Compact widgets beside a large navigation card: the card gets most of the width. */
    private void mapLayout(int gap,boolean driverLeft) {
        boolean compact = narrow || shortWindow;
        // Navigation is fixed on the left, where Maps' picture-in-picture window also sits; the bar is on the right.
        size(contextColumn,0,-1,navigationWeight(compact,gap),gap,0);
        size(energyColumn,0,-1,1,0,0);
        margins(contextColumn,0,0);
        size(navigation,-1,0,1,0,0); margins(navigation,0,0);
        navigationCard(compact);
        size(energy,-1,0,1,0,0); margins(energy,0,0);
        pad(energy,compact?12:16,compact?8:12,compact?12:16,compact?8:10);
        fit(R.id.energy_soc,compact?30:36,compact?80:120);
        fit(R.id.energy_range,compact?20:24,compact?52:80);
        width(host.findViewById(R.id.energy_battery_icon),compact?26:36);
        height(host.findViewById(R.id.energy_battery_icon),compact?36:50);
        margins(host.findViewById(R.id.energy_range_column),compact?8:14,0);
        host.findViewById(R.id.energy_reference_row).setVisibility(View.GONE);
        host.findViewById(R.id.energy_result).setVisibility(View.GONE);
        size(telemetry,-1,shortWindow?(veryShort?96:108):Math.round((driving?136:120)*typeScale),0,0,0); margins(telemetry,0,gap);
        size(mediaColumn,-1,shortWindow?(veryShort?96:108):Math.round(116*typeScale),0,0,0); margins(mediaColumn,0,gap);
    }

    /**
     * The navigation column's weight against the widget column's 1. The PiP tile grows with the screen's
     * height, so on a taller screen the guiding card widens to keep the guidance its room (the 352 dp it
     * has on the car's 1920x898 screen), leaving the widgets at least 360 dp.
     */
    private float navigationWeight(boolean compact,int gap) {
        float weight=narrow?1.2f:1.35f;
        if(!guiding||narrow)return weight;
        int total=host.getResources().getConfiguration().screenWidthDp-2*(compact?10:14)-gap;
        int need=Math.min(pipClearance()+(compact?16:24)+352,total-360);
        return need>0?Math.max(weight,need/(float)(total-need)):weight;
    }

    /** Header height (dp) that starts the cards 8 px above the PiP tile, as they end 8 px below it. */
    private int pipHeader() {
        android.util.DisplayMetrics m=host.getResources().getDisplayMetrics();
        int tile=Math.round(Math.min(m.widthPixels,m.heightPixels)*0.5412f*4/3);
        return Math.round((m.heightPixels-dp(80)-tile-8-2*dp(10))/m.density);
    }

    /**
     * Width to keep clear on the left while Google Maps guides as a picture-in-picture tile. The box's
     * boot-tune service sets the PiP to 44.25 % (oablPipSize), which at Maps' 3:4 shape is about 0.54 of the
     * display's short edge wide (486 × 648 px on the box: the card's height less 8 px above and below), plus its 15 px margin and a gap.
     */
    private int pipClearance() {
        android.content.res.Configuration c = host.getResources().getConfiguration();
        return Math.round(Math.min(c.screenWidthDp, c.screenHeightDp) * 0.545f) + 28;
    }

    /**
     * How far (px) the guiding card moves its left edge out so Maps' PiP tile sits inside it with the
     * same margin on the left as below it. The tile itself can't be moved sideways: SystemUI keeps it
     * 10 dp from the screen's left edge and 80 dp above its bottom (this firmware's WMShell insets).
     */
    private int pipReach() {
        int[] at=new int[2];
        contextColumn.getLocationOnScreen(at);
        View decor=host.getWindow().getDecorView();
        if(contextColumn.getWidth()==0||decor.getHeight()==0)return 0;
        int below=at[1]+contextColumn.getHeight()-(decor.getHeight()-dp(80));
        if(below<=0)return 0;
        return Math.max(0,at[0]-Math.max(0,dp(10)-below));
    }

    /** The big navigation card: idle shortcuts, or guidance with its footer kept clear of the PiP corner. */
    private void navigationCard(boolean compact) {
        // While guiding, Maps' picture-in-picture window sits over the card's bottom-left corner:
        // everything on the card starts to its right.
        int reach=guiding?pipReach():0;
        pad(navigation,guiding?pipClearance()+Math.round(reach/host.getResources().getDisplayMetrics().density):compact?16:24,compact?12:20,compact?16:24,compact?12:18);
        // The card reaches left past its column (and the screen padding) so the tile sits inside it.
        ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)navigation.getLayoutParams();
        if(lp.getMarginStart()!=-reach){lp.setMarginStart(-reach);navigation.setLayoutParams(lp);}
        for(View v:new View[]{contextColumn,panel,host.findViewById(R.id.stage),host.findViewById(R.id.home_content)}){
            if(v instanceof ViewGroup){((ViewGroup)v).setClipChildren(reach==0);((ViewGroup)v).setClipToPadding(reach==0);}
        }
        // The big card always has room for its title row (and the navigation app chip).
        ((View)host.findViewById(R.id.navigation_hint_label).getParent()).setVisibility(View.VISIBLE);
        text(R.id.navigation_hint_label,compact?11:13);
        text(R.id.navigation_where,narrow?28:shortWindow?34:40);
        // Content starts under the title instead of floating in the middle of the card.
        ((LinearLayout)host.findViewById(R.id.navigation_idle)).setGravity(Gravity.TOP);
        margins(host.findViewById(R.id.navigation_idle),0,compact?8:14);
        // Guidance has the same room on every screen (navigationWeight), so the same sizes. The distance
        // shares its row with the ETA, so it shrinks further rather than being cut to "3.2 …".
        fit(R.id.navigation_hint_title,narrow?22:26,narrow?46:72);
        text(R.id.navigation_hint_detail,narrow?18:26);
        ((TextView)host.findViewById(R.id.navigation_hint_detail)).setMaxLines(narrow?3:2);
        text(R.id.navigation_eta,narrow?22:28);
        text(R.id.navigation_hint_trip,narrow?14:18);
        text(R.id.navigation_arrival_soc,13);
        text(R.id.navigation_end,14);
        height((View)host.findViewById(R.id.navigation_end).getParent(),36);
        margins((View)host.findViewById(R.id.navigation_end).getParent(),0,8);
        height(host.findViewById(R.id.navigation_end),36);
        // Sized to fill the card's height: the turn is what this card is for.
        int turnIcon=narrow?62:Math.round(132*typeScale);
        width(host.findViewById(R.id.navigation_hint_icon),turnIcon);
        height(host.findViewById(R.id.navigation_hint_icon),turnIcon);
        height(host.findViewById(R.id.navigation_turn_progress),6);
        margins(host.findViewById(R.id.navigation_turn_progress),0,10);
    }

    private void navigationFocus(int gap) {
        boolean compact = narrow || shortWindow;
        // Guidance takes the primary column. Battery and speed remain readable beside it.
        size(navigation,-1,0,1,0,0); margins(navigation,0,0);
        // Stack weather over the media controls only in a narrow, tall split; a short window has
        // no room to stack, so they sit side by side with enough height for the transport buttons.
        boolean stackRow = narrow && host.getResources().getConfiguration().screenHeightDp > 560;
        size(compactRow,-1,stackRow?194:shortWindow?112:144,0,0,0); margins(compactRow,0,gap);
        compactRow.setOrientation(stackRow?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        if (stackRow) {
            size(weather,-1,68,0,0,0); size(mediaColumn,-1,0,1,0,0); margins(mediaColumn,0,gap);
        } else if (narrow) {
            size(weather,0,-1,.8f,gap,0); margins(weather,0,0);
            size(mediaColumn,0,-1,1.35f,0,0); margins(mediaColumn,0,0);
        }
        size(energy,-1,0,shortWindow?1.2f:1.15f,0,0); margins(energy,0,0);
        size(telemetry,-1,0,1,0,0); margins(telemetry,0,gap);
        pad(energy,compact?12:18,compact?9:14,compact?12:18,compact?8:12);
        fit(R.id.energy_soc,narrow?30:shortWindow?34:40,narrow?64:shortWindow?80:110);
        fit(R.id.energy_range,16,narrow?44:shortWindow?54:76);
        text(R.id.energy_soc_note,compact?10:12); text(R.id.energy_range_note,compact?10:12);
        text(R.id.energy_reference_row,compact?10:11); text(R.id.energy_result,compact?9:10);
        width(host.findViewById(R.id.energy_battery_icon),compact?26:34);
        height(host.findViewById(R.id.energy_battery_icon),compact?36:46);
        margins(host.findViewById(R.id.energy_range_column),compact?6:10,0);
        text(R.id.speed_value,shortWindow?64:narrow?80:120);
        pad(navigation,compact?16:24,compact?12:20,compact?16:24,compact?12:18);
        ((View)host.findViewById(R.id.navigation_hint_label).getParent()).setVisibility(shortWindow?View.GONE:View.VISIBLE);
        text(R.id.navigation_hint_label,compact?11:13);
        fit(R.id.navigation_hint_title,narrow?30:40,narrow?46:shortWindow?58:76);
        text(R.id.navigation_hint_detail,narrow?18:shortWindow?22:26);
        ((TextView)host.findViewById(R.id.navigation_hint_detail)).setMaxLines(narrow?3:2);
        text(R.id.navigation_eta,narrow?22:shortWindow?26:32);
        text(R.id.navigation_hint_trip,compact?13:16);
        text(R.id.navigation_arrival_soc,13);
        text(R.id.navigation_end,compact?14:16);
        height((View)host.findViewById(R.id.navigation_end).getParent(),compact?36:44);
        margins((View)host.findViewById(R.id.navigation_end).getParent(),0,compact?8:12);
        height(host.findViewById(R.id.navigation_end),compact?36:44);
        width(host.findViewById(R.id.navigation_hint_icon),narrow?62:shortWindow?84:108);
        height(host.findViewById(R.id.navigation_hint_icon),narrow?62:shortWindow?84:108);
        height(host.findViewById(R.id.navigation_turn_progress),6);
        margins(host.findViewById(R.id.navigation_turn_progress),0,compact?10:16);
    }

    private void mediaFocus(int gap) {
        boolean compact = narrow || shortWindow;
        // With no car connection or route, use the released space for the actual player.
        size(mediaColumn,-1,-1,0,0,0); margins(mediaColumn,0,0);
        size(telemetry,-1,shortWindow?132:narrow?150:Math.round(176*typeScale),0,0,0); margins(telemetry,0,0);
        size(navigation,-1,0,1,0,0); margins(navigation,0,gap);
        size(weather,-1,shortWindow?80:narrow?92:Math.round(116*typeScale),0,0,0); margins(weather,0,gap);
        pad(weather,compact?12:16,compact?6:10,compact?12:16,compact?6:10);
        pad(host.findViewById(R.id.media_content),compact?16:24,compact?12:20,compact?16:24,compact?10:16);
        LinearLayout mediaInfo = host.findViewById(R.id.media_info_row);
        mediaInfo.setOrientation(narrow?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        mediaInfo.setGravity(Gravity.CENTER);
        View mediaText = host.findViewById(R.id.media_text);
        size(mediaText,narrow?-1:0,-2,narrow?0:1,0,0);
        margins(mediaText,narrow?0:24,narrow?10:0);
        // The player fills the primary column: big artwork, title and controls you can hit blind.
        int art = narrow?150:shortWindow?168:Math.round(280*typeScale);
        width(host.findViewById(R.id.media_artwork),art); height(host.findViewById(R.id.media_artwork),art);
        host.findViewById(R.id.media_header).setVisibility(View.VISIBLE);
        text(R.id.media_section_label,compact?11:13); text(R.id.media_source,compact?11:13);
        ((TextView)host.findViewById(R.id.media_source)).setMaxWidth(dp(narrow?120:180));
        text(R.id.media_title,narrow?28:shortWindow?34:52);
        ((TextView)host.findViewById(R.id.media_title)).setMaxLines(2);
        text(R.id.media_subtitle,narrow?16:shortWindow?19:26);
        ((TextView)host.findViewById(R.id.media_subtitle)).setMaxLines(narrow?1:2);
        // No progress bar on Home: its once-a-second repaint costs a video frame on the car screen.
        // The info row's weight takes the space; the full player keeps the timeline.
        host.findViewById(R.id.media_progress).setVisibility(View.GONE);
        height(host.findViewById(R.id.media_progress),0);
        margins(host.findViewById(R.id.media_controls),0,compact?4:8);
        int button = narrow?56:compact?64:Math.round(84*typeScale);
        height(host.findViewById(R.id.media_controls),button);
        // Every control takes an equal share of the row, never more than the button size. A fixed size per
        // button overflowed on a big or dense window once the star was added, pushing "previous" off the card.
        for(int id:new int[]{R.id.media_previous,R.id.media_play_pause,R.id.media_next,R.id.media_playlists,R.id.media_expand,R.id.media_favorite,R.id.media_eq}) {
            size(host.findViewById(id),0,button,1,0,0);
            int p=dp(Math.round(button*.26f)); host.findViewById(id).setPadding(p,p,p,p);
        }
        host.findViewById(R.id.media_open).setVisibility(View.VISIBLE);
        height(host.findViewById(R.id.media_open),button);
        text(R.id.media_open,compact?15:18);
        host.findViewById(R.id.media_controls_spacer).setVisibility(View.GONE);
    }

    /** Moving a view only when its slot changes avoids churn on normal renderer refreshes. */
    private static void place(LinearLayout parent,int index,View child) {
        if(child.getParent()==parent && parent.indexOfChild(child)==index)return;
        detach(child); parent.addView(child,Math.min(index,parent.getChildCount()));
    }

    /** End disappearing-child transitions before reparenting the driver's column. */
    private static void detach(View child) {
        ViewGroup parent=(ViewGroup)child.getParent(); if (parent==null) return;
        android.animation.LayoutTransition transition=parent.getLayoutTransition();
        parent.setLayoutTransition(null); parent.removeView(child); parent.endViewTransition(child); parent.setLayoutTransition(transition);
    }
    private void fit(int id,int min,int max) {
        TextView view=host.findViewById(id);
        // The hero numbers scale with the window, so a taller screen gets bigger digits. The range
        // stays an auto-sizing band so a long value still shrinks to fit rather than clipping.
        int smin=Math.round(min*typeScale), smax=Math.round(max*typeScale);
        // Reset the measuring size too: changing only auto-size bounds can retain the old taller
        // wrap-content box after a window resize, leaving the battery notes outside the hero row.
        if(android.os.Build.VERSION.SDK_INT>=26) view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        view.setTextSize(smax);
        if(android.os.Build.VERSION.SDK_INT>=26) view.setAutoSizeTextTypeUniformWithConfiguration(smin,smax,1,android.util.TypedValue.COMPLEX_UNIT_SP);
    }
    private void text(int id,int size) {
        TextView view=host.findViewById(id);
        if(android.os.Build.VERSION.SDK_INT>=26)view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        // Every label grows with the window but never falls below a legible floor.
        view.setTextSize(Math.max(minLabelSp, size*typeScale));
    }
    /** A size that is not scaled with the window, for quiet lines that must stay small. */
    private void fixed(int id,int size) {
        TextView view=host.findViewById(id);
        if(android.os.Build.VERSION.SDK_INT>=26)view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        view.setTextSize(size);
    }
    private void pad(View v,int l,int t,int r,int b) { v.setPadding(dp(l),dp(t),dp(r),dp(b)); }
    private void size(View view,int w,int h,float weight,int end,int bottom) {
        LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)view.getLayoutParams();
        int width=w<0?w:dp(w),height=h<0?h:dp(h);
        if (p.width==width && p.height==height && p.weight==weight && p.getMarginEnd()==dp(end) && p.bottomMargin==dp(bottom)) return;
        p.width=width; p.height=height; p.weight=weight; p.setMarginEnd(dp(end)); p.bottomMargin=dp(bottom); view.setLayoutParams(p);
    }
    private void margins(View view,int start,int top) {
        ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)view.getLayoutParams();
        if (p.getMarginStart()==dp(start) && p.topMargin==dp(top)) return;
        p.setMarginStart(dp(start)); p.topMargin=dp(top); view.setLayoutParams(p);
    }
    private void height(View v,int value) { int px=value<0?value:dp(value);if (v.getLayoutParams().height!=px) {v.getLayoutParams().height=px;v.requestLayout();} }
    private void width(View v,int value) { int px=value<0?value:dp(value);if(v.getLayoutParams().width!=px){v.getLayoutParams().width=px;v.requestLayout();} }
}
