package com.tvdash.launcher;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * View-only work orders. List, map, and reports. Back leaves for the normal dashboard.
 * Nothing here edits a job, and money, contacts, and street addresses are not shown.
 */
public class WorkActivity extends Activity {
    private static final long POLL_MS = 120L * 1000L;
    private static final String[] SORTS = {"SLA", "Priority", "State", "Phase", "Newest"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Prefs prefs;
    private TextView banner;
    private TextView tabList;
    private TextView tabMap;
    private TextView tabReports;
    private TextView zoomButton;
    private TextView mapCaption;
    private LinearLayout mapStats;
    private LinearLayout legend;
    private LinearLayout filters;
    private LinearLayout list;
    private LinearLayout detailLines;
    private TextView detailTitle;
    private WorkMapView map;
    private LinearLayout reportBox;
    private View body;
    private View reports;
    private View listPage;
    private View mapPage;
    private int page;
    private int sort;
    private int gen;
    private boolean resumed;
    private boolean hotOnly;
    private String phaseF = "All";
    private String statusF = "All";
    private String priorityF = "All";
    private String stateF = "All";
    private String selectedWo = "";
    private String zoom;
    private WorkClient.Result data = new WorkClient.Result(new ArrayList<WorkOrder>(), "", false);

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            load(true);
            ui.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Vigo.NAVY);
        int side = Ui.dp(this, 28);
        root.setPadding(side, Ui.dp(this, 16), side, Ui.dp(this, 12));
        root.addView(header());

        FrameLayout stage = new FrameLayout(this);
        body = workBody();
        reports = reportPage();
        reports.setVisibility(View.GONE);
        stage.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(reports, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        showPage(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(poll);
        load(false);
        ui.postDelayed(poll, POLL_MS);
        if (page == 0 && map != null) map.startAmbient();
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(poll);
        if (map != null) map.stopAmbient();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void load(final boolean bypassCache) {
        final int ticket = ++gen;
        final String key = prefs.workBearer();
        io.execute(new Runnable() {
            @Override public void run() {
                final WorkClient.Result result = WorkClient.load(WorkActivity.this, key, bypassCache);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (ticket != gen || isFinishing()) return;
                        data = result;
                        apply();
                    }
                });
            }
        });
    }

    private void apply() {
        if (data.demo) banner.setText("DEMO DATA");
        else if (data.notice.length() > 0) banner.setText(data.notice.toUpperCase());
        else banner.setText("LIVE");
        int color = data.demo ? Vigo.GOLD : (data.notice.length() > 0 ? Vigo.CORAL : Vigo.AQUA);
        banner.setTextColor(color);
        rebuildFilters();
        rebuildList();
        refreshMap();
        rebuildReports();
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, Ui.dp(this, 10));

        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int s = Ui.dp(this, 36);
        row.addView(logo, new LinearLayout.LayoutParams(s, s));
        Vigo.logo(this, logo);

        TextView word = Vigo.text(this, "VIGO", 22, Vigo.TEXT, true);
        word.setLetterSpacing(0.28f);
        word.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 18), 0);
        row.addView(word);

        tabMap = tab("Map", 0);
        tabList = tab("List", 1);
        tabReports = tab("Reports", 2);
        row.addView(tabMap);
        row.addView(tabList);
        row.addView(tabReports);

        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        banner = Vigo.text(this, "LOADING", 12, Vigo.GOLD, true);
        banner.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        banner.setBackground(Vigo.panel(this));
        row.addView(banner);

        TextView back = Vigo.text(this, "Dashboard", 14, Vigo.AQUA, false);
        back.setFocusable(true);
        back.setBackground(Vigo.panel(this));
        back.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMarginStart(Ui.dp(this, 10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        row.addView(back, blp);
        return row;
    }

    private TextView tab(final String label, final int index) {
        final TextView t = Vigo.text(this, label, 16, Vigo.TEXT, true);
        t.setFocusable(true);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 8));
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPage(index); }
        });
        t.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent ev) {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { showPage((index + 1) % 3); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { showPage((index + 2) % 3); return true; }
                return false;
            }
        });
        return t;
    }

    private void showPage(int index) {
        page = index;
        styleTab(tabMap, index == 0);
        styleTab(tabList, index == 1);
        styleTab(tabReports, index == 2);
        boolean charts = index == 2;
        body.setVisibility(charts ? View.GONE : View.VISIBLE);
        reports.setVisibility(charts ? View.VISIBLE : View.GONE);
        if (!charts) {
            mapPage.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
            listPage.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        }
        if (index == 0 && resumed && map != null) map.startAmbient();
        else if (map != null) map.stopAmbient();
        if (index == 0 && map != null) map.requestFocus();
        else if (index == 2) reports.requestFocus();
    }

    private void styleTab(TextView tab, boolean on) {
        tab.setTextColor(on ? Vigo.NAVY : Vigo.TEXT);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(Ui.dp(this, 6));
        d.setColor(on ? Vigo.CYAN : Vigo.NAVY_2);
        d.setStroke(1, on ? Vigo.CYAN : Vigo.LINE);
        tab.setBackground(d);
    }

    private View workBody() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout left = new FrameLayout(this);
        listPage = listPage();
        mapPage = mapPage();
        listPage.setVisibility(View.GONE);
        left.addView(listPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        left.addView(mapPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        row.addView(detailCard(), detailLp());
        return row;
    }

    private View listPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFocusable(false);
        filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(0, 0, 0, Ui.dp(this, 8));
        scroller.addView(filters);
        col.addView(scroller);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private View mapPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(0, 0, 0, Ui.dp(this, 8));
        zoomButton = Vigo.text(this, "Zoom state", 14, Vigo.AQUA, true);
        zoomButton.setFocusable(true);
        zoomButton.setBackground(Vigo.panel(this));
        zoomButton.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        zoomButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleZoom(); }
        });
        tools.addView(zoomButton);
        mapCaption = Vigo.text(this, "United States", 14, Vigo.MUTED, false);
        mapCaption.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        tools.addView(mapCaption);
        col.addView(tools);

        mapStats = new LinearLayout(this);
        mapStats.setOrientation(LinearLayout.HORIZONTAL);
        mapStats.setPadding(0, 0, 0, Ui.dp(this, 8));
        col.addView(mapStats);

        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.HORIZONTAL);
        map = new WorkMapView(this);
        map.setListener(new WorkMapView.Select() {
            @Override public void onJob(WorkOrder job) { showDetail(job); }
            @Override public void onOpen(WorkOrder job) {
                showDetail(job);
                if (job != null) setZoom(job.state);
            }
        });
        stage.addView(map, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        ScrollView legendScroll = new ScrollView(this);
        legendScroll.setFocusable(false);
        legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.VERTICAL);
        legend.setPadding(Ui.dp(this, 12), 0, 0, 0);
        legendScroll.addView(legend);
        LinearLayout.LayoutParams legendLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 196), ViewGroup.LayoutParams.MATCH_PARENT);
        stage.addView(legendScroll, legendLp);
        col.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private LinearLayout.LayoutParams detailLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 340), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMarginStart(Ui.dp(this, 12));
        return lp;
    }

    private View detailCard() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Vigo.panel(this));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        box.setPadding(p, p, p, p);
        detailTitle = Vigo.text(this, "Work order", 26, Vigo.TEXT, true);
        box.addView(detailTitle);
        detailLines = new LinearLayout(this);
        detailLines.setOrientation(LinearLayout.VERTICAL);
        detailLines.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(detailLines);
        scroll.addView(box);
        return scroll;
    }

    private View reportPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent ev) {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                int step = Ui.dp(WorkActivity.this, 90);
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    ((ScrollView) v).smoothScrollBy(0, step);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (((ScrollView) v).getScrollY() <= 0) return false;
                    ((ScrollView) v).smoothScrollBy(0, -step);
                    return true;
                }
                return false;
            }
        });
        reportBox = new LinearLayout(this);
        reportBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(reportBox);
        return scroll;
    }

    private void rebuildFilters() {
        filters.removeAllViews();
        filters.addView(filterChip("Phase", phaseF, choices("phase"), new Pick() {
            @Override public void on(String value) { phaseF = value; refreshViews(); }
        }));
        filters.addView(filterChip("Status", statusF, choices("status"), new Pick() {
            @Override public void on(String value) { statusF = value; refreshViews(); }
        }));
        filters.addView(filterChip("Priority", priorityF, choices("priority"), new Pick() {
            @Override public void on(String value) { priorityF = value; refreshViews(); }
        }));
        filters.addView(filterChip("State", stateF, choices("state"), new Pick() {
            @Override public void on(String value) { stateF = value; refreshViews(); }
        }));
        filters.addView(filterChip("Hot", hotOnly ? "Hot only" : "All", hotChoices(), new Pick() {
            @Override public void on(String value) { hotOnly = "Hot only".equals(value); refreshViews(); }
        }));
        filters.addView(filterChip("Sort", SORTS[sort], sortChoices(), new Pick() {
            @Override public void on(String value) {
                for (int i = 0; i < SORTS.length; i++) if (SORTS[i].equals(value)) sort = i;
                refreshViews();
            }
        }));
    }

    private interface Pick { void on(String value); }

    private TextView filterChip(final String name, String current, final List<String> options, final Pick pick) {
        final TextView chip = Vigo.text(this, name + "  " + current, 13, Vigo.TEXT, false);
        chip.setFocusable(true);
        chip.setBackground(Vigo.panel(this));
        chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 8));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (options.isEmpty()) return;
                int at = 0;
                String now = chip.getText().toString();
                int space = now.indexOf("  ");
                String cur = space >= 0 ? now.substring(space + 2) : options.get(0);
                for (int i = 0; i < options.size(); i++) if (options.get(i).equals(cur)) at = i;
                String next = options.get((at + 1) % options.size());
                chip.setText(name + "  " + next);
                pick.on(next);
            }
        });
        return chip;
    }

    private void refreshViews() {
        rebuildList();
        refreshMap();
        rebuildReports();
    }

    private List<String> choices(String field) {
        List<String> out = new ArrayList<String>();
        out.add("All");
        for (int i = 0; i < data.orders.size(); i++) {
            WorkOrder order = data.orders.get(i);
            String value = "phase".equals(field) ? order.phase
                    : "status".equals(field) ? order.statusName
                    : "priority".equals(field) ? order.priority
                    : order.state;
            if (value.length() > 0 && !out.contains(value)) out.add(value);
        }
        return out;
    }

    private List<String> hotChoices() {
        List<String> out = new ArrayList<String>();
        out.add("All");
        out.add("Hot only");
        return out;
    }

    private List<String> sortChoices() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < SORTS.length; i++) out.add(SORTS[i]);
        return out;
    }

    private List<WorkOrder> filtered() {
        List<WorkOrder> out = new ArrayList<WorkOrder>();
        for (int i = 0; i < data.orders.size(); i++) {
            WorkOrder order = data.orders.get(i);
            if (!"All".equals(phaseF) && !phaseF.equals(order.phase)) continue;
            if (!"All".equals(statusF) && !statusF.equals(order.statusName)) continue;
            if (!"All".equals(priorityF) && !priorityF.equals(order.priority)) continue;
            if (!"All".equals(stateF) && !stateF.equals(order.state)) continue;
            if (hotOnly && !order.hotJob) continue;
            out.add(order);
        }
        Collections.sort(out, new Comparator<WorkOrder>() {
            @Override public int compare(WorkOrder a, WorkOrder b) {
                if (sort == 1) return WorkOrder.priorityRank(a.priority) - WorkOrder.priorityRank(b.priority);
                if (sort == 2) return a.state.compareTo(b.state);
                if (sort == 3) return a.phase.compareTo(b.phase);
                if (sort == 4) return WorkOrder.day(b.createdAt).compareTo(WorkOrder.day(a.createdAt));
                String as = WorkOrder.day(a.slaCompletionDate);
                String bs = WorkOrder.day(b.slaCompletionDate);
                if (as.length() == 0) return 1;
                if (bs.length() == 0) return -1;
                return as.compareTo(bs);
            }
        });
        return out;
    }

    private void rebuildList() {
        list.removeAllViews();
        if (data.notice.length() > 0 && !data.demo && data.orders.isEmpty()) {
            list.addView(note(noticeLine()));
            showDetail(null);
            return;
        }
        List<WorkOrder> rows = filtered();
        if (rows.isEmpty()) {
            list.addView(note(data.orders.isEmpty() ? "No open work orders." : "Nothing matches these filters."));
            showDetail(null);
            return;
        }
        boolean known = false;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).woNumber.equals(selectedWo)) known = true;
        if (!known) selectedWo = rows.get(0).woNumber;
        for (int i = 0; i < rows.size(); i++) list.addView(listRow(rows.get(i)));
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).woNumber.equals(selectedWo)) showDetail(rows.get(i));
        }
    }

    private String noticeLine() {
        if ("Invalid key".equals(data.notice)) {
            return "Invalid key. Check the key in settings. Demo data stays off while a key is saved.";
        }
        if (data.notice.length() > 0) return data.notice;
        return "No open work orders.";
    }

    private TextView note(String text) {
        TextView t = Vigo.text(this, text, 16, Vigo.TEXT, false);
        t.setPadding(0, Ui.dp(this, 12), Ui.dp(this, 12), 0);
        return t;
    }

    private View listRow(final WorkOrder order) {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        int p = Ui.dp(this, 8);
        row.setPadding(p, p, p, p);
        final GradientDrawable bg = Vigo.panel(this);
        if (order.overdue()) bg.setStroke(Ui.dp(this, 2), Vigo.CORAL);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 6);
        row.setLayoutParams(lp);

        TextView wo = Vigo.text(this, order.woNumber, 14, Vigo.TEXT, true);
        row.addView(wo, cell(Ui.dp(this, 118)));
        TextView status = Vigo.pill(this, order.statusName.length() == 0 ? "—" : order.statusName, order.statusBg, order.statusFg);
        row.addView(status, cell(ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(ellipsize(order.phase, 14, Vigo.TEXT), cell(Ui.dp(this, 120)));
        int priorityTint = WorkOrder.priorityColor(order.priority);
        row.addView(ellipsize(order.priority, 14, priorityTint == 0 ? Vigo.TEXT : priorityTint), cell(Ui.dp(this, 90)));
        String place = order.store.length() == 0 ? order.place() : order.store + "  " + order.place();
        row.addView(ellipsize(place, 14, Vigo.TEXT), cell(0));
        row.addView(ellipsize(order.trade, 14, WorkOrder.tradeColor(order.trade)), cell(Ui.dp(this, 110)));
        TextView sla = Vigo.text(this, WorkOrder.pretty(order.slaCompletionDate), 14,
                order.overdue() ? Vigo.RED : Vigo.TEXT, false);
        row.addView(sla, cell(Ui.dp(this, 72)));
        if (order.hotJob) row.addView(Vigo.pill(this, "HOT", Vigo.CORAL, Vigo.NAVY));

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                bg.setStroke(Ui.dp(WorkActivity.this, has || order.overdue() ? 2 : 1),
                        has ? Vigo.CYAN : (order.overdue() ? Vigo.CORAL : Vigo.LINE));
                if (has) {
                    selectedWo = order.woNumber;
                    showDetail(order);
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selectedWo = order.woNumber;
                showDetail(order);
            }
        });
        return row;
    }

    private LinearLayout.LayoutParams cell(int width) {
        if (width == 0) {
            return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 8));
        return lp;
    }

    private TextView ellipsize(String value, int sp, int color) {
        TextView t = Vigo.text(this, value == null ? "" : value, sp, color, false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private void showDetail(WorkOrder order) {
        detailLines.removeAllViews();
        if (order == null) {
            detailTitle.setText(data.notice.length() > 0 && !data.demo ? data.notice : "Work order");
            if (data.notice.length() > 0 && !data.demo) detailLines.addView(note(noticeLine()));
            else detailLines.addView(note("Select a work order."));
            return;
        }
        detailTitle.setText(order.woNumber);
        LinearLayout pills = new LinearLayout(this);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        pills.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 6));
        if (order.statusName.length() > 0) {
            pills.addView(Vigo.pill(this, order.statusName, order.statusBg, order.statusFg));
        }
        if (order.hotJob) {
            TextView hot = Vigo.pill(this, "HOT", Vigo.CORAL, Vigo.NAVY);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(Ui.dp(this, 8));
            pills.addView(hot, lp);
        }
        detailLines.addView(pills);
        line("Phase", order.phase);
        line("Priority", order.priority);
        line("Store", order.store);
        line("Where", order.place());
        line("Client", order.client);
        line("Trade", order.trade);
        line("Type", order.woType);
        line("Requested", order.workRequested);
        line("SLA", WorkOrder.pretty(order.slaCompletionDate) + (order.overdue() ? "  overdue" : ""));
        line("Status changed", WorkOrder.pretty(order.statusChangedAt));
        line("Opened", WorkOrder.pretty(order.createdAt));
        line("Vendor", order.vendors);
        line("Next visit", WorkOrder.pretty(order.nextVisitDate));
        TextView view = Vigo.text(this, "View only", 12, Vigo.MUTED, false);
        view.setPadding(0, Ui.dp(this, 10), 0, 0);
        detailLines.addView(view);
    }

    private void line(String label, String value) {
        TextView k = Vigo.text(this, label.toUpperCase(), 11, Vigo.MUTED, true);
        k.setLetterSpacing(0.08f);
        k.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        detailLines.addView(k);
        TextView v = Vigo.text(this, value == null || value.length() == 0 ? "—" : value, 16, Vigo.TEXT, false);
        detailLines.addView(v);
    }

    private void toggleZoom() {
        if (zoom != null) {
            setZoom(null);
            return;
        }
        WorkOrder order = findSelected();
        if (order != null) setZoom(order.state);
    }

    private void setZoom(String state) {
        zoom = state == null || state.length() == 0 ? null : WorkGeo.stateCode(state);
        if (zoomButton != null) zoomButton.setText(zoom == null ? "Zoom state" : "All states");
        if (mapCaption != null) {
            mapCaption.setText(zoom == null ? "United States" : WorkGeo.stateName(zoom));
        }
        refreshMap();
    }

    private void refreshMap() {
        if (map == null) return;
        List<WorkOrder> rows = filtered();
        fillMapStats(rows);
        if (zoom != null) {
            List<WorkOrder> inState = new ArrayList<WorkOrder>();
            for (int i = 0; i < rows.size(); i++) {
                if (WorkGeo.stateCode(rows.get(i).state).equals(zoom)) inState.add(rows.get(i));
            }
            rows = inState;
        }
        fillLegend(rows);
        map.setZoom(zoom);
        map.setJobs(rows, selectedWo);
    }

    private void fillMapStats(List<WorkOrder> rows) {
        if (mapStats == null) return;
        mapStats.removeAllViews();
        int hot = 0;
        int overdue = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).hotJob) hot++;
            if (rows.get(i).overdue()) overdue++;
        }
        mapStats.addView(mapStat("Open", rows.size(), Vigo.TEXT), kpiLp());
        mapStats.addView(mapStat("Hot", hot, Vigo.CORAL), kpiLp());
        mapStats.addView(mapStat("Overdue", overdue, Vigo.RED), kpiLp());
        mapStats.addView(mapStat("On time", rows.size() - overdue, Vigo.GREEN), kpiLp());
    }

    private View mapStat(String label, int value, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Vigo.panel(this));
        int p = Ui.dp(this, 10);
        box.setPadding(p, p, p, p);
        box.addView(Vigo.text(this, label.toUpperCase(), 11, Vigo.MUTED, true));
        TextView num = Vigo.text(this, String.valueOf(value), 28, color, true);
        num.setPadding(0, Ui.dp(this, 2), 0, 0);
        box.addView(num);
        return box;
    }

    private void fillLegend(List<WorkOrder> rows) {
        if (legend == null) return;
        legend.removeAllViews();
        TextView title = Vigo.text(this, "TRADE", 12, Vigo.AQUA, true);
        title.setPadding(0, 0, 0, Ui.dp(this, 8));
        legend.addView(title);
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            String name = rows.get(i).trade;
            if (name.length() == 0 || names.contains(name)) continue;
            names.add(name);
        }
        Collections.sort(names, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int rank = WorkOrder.tradeRank(a) - WorkOrder.tradeRank(b);
                return rank != 0 ? rank : a.compareToIgnoreCase(b);
            }
        });
        for (int i = 0; i < names.size(); i++) legend.addView(legendRow(names.get(i), WorkOrder.tradeColor(names.get(i))));
        TextView hot = Vigo.text(this, "Ring   hot", 13, Vigo.CORAL, false);
        hot.setPadding(0, Ui.dp(this, 10), 0, 0);
        legend.addView(hot);
    }

    private View legendRow(String name, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        View swatch = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(Ui.dp(this, 4));
        d.setColor(color);
        swatch.setBackground(d);
        int s = Ui.dp(this, 12);
        row.addView(swatch, new LinearLayout.LayoutParams(s, s));
        TextView label = Vigo.text(this, name, 14, Vigo.TEXT, false);
        label.setPadding(Ui.dp(this, 8), 0, 0, 0);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private WorkOrder findSelected() {
        for (int i = 0; i < data.orders.size(); i++) {
            if (data.orders.get(i).woNumber.equals(selectedWo)) return data.orders.get(i);
        }
        List<WorkOrder> rows = filtered();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void rebuildReports() {
        reportBox.removeAllViews();
        List<WorkOrder> rows = filtered();
        LinearLayout kpis = new LinearLayout(this);
        kpis.setOrientation(LinearLayout.HORIZONTAL);
        int open = rows.size();
        int hot = 0;
        int overdue = 0;
        int week = 0;
        for (int i = 0; i < rows.size(); i++) {
            WorkOrder order = rows.get(i);
            if (order.hotJob) hot++;
            if (order.overdue()) overdue++;
            if (WorkOrder.thisWeek(order.nextVisitDate)) week++;
        }
        int onTime = open - overdue;
        kpis.addView(kpi("Open", open, Vigo.TEXT), kpiLp());
        kpis.addView(kpi("Hot", hot, Vigo.CORAL), kpiLp());
        kpis.addView(kpi("Overdue", overdue, Vigo.RED), kpiLp());
        kpis.addView(kpi("On time", onTime, Vigo.GREEN), kpiLp());
        kpis.addView(kpi("This week", week, Vigo.CYAN), kpiLp());
        reportBox.addView(kpis);

        chart("By phase", tally(rows, "phase"), Vigo.TEAL);
        chart("By state", tally(rows, "state"), Vigo.TEAL_2);
        chart("By trade", tally(rows, "trade"), Vigo.CYAN);
        chart("By client", tally(rows, "client"), Vigo.AQUA);
        chart("Age since opened", aging(rows, true), Vigo.GOLD);
        chart("Age since status change", aging(rows, false), Vigo.GOLD);
        chart("Reactive and recurring", tally(rows, "type"), Vigo.TEAL);
    }

    private LinearLayout.LayoutParams kpiLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(Ui.dp(this, 8));
        return lp;
    }

    private View kpi(String label, int value, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Vigo.panel(this));
        int p = Ui.dp(this, 12);
        box.setPadding(p, p, p, p);
        box.addView(Vigo.text(this, label.toUpperCase(), 11, Vigo.MUTED, true));
        TextView num = Vigo.text(this, String.valueOf(value), 32, color, true);
        num.setPadding(0, Ui.dp(this, 4), 0, 0);
        box.addView(num);
        return box;
    }

    private void chart(String title, List<WorkBars.Row> rows, int color) {
        TextView heading = Vigo.text(this, title, 16, Vigo.TEXT, true);
        heading.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
        reportBox.addView(heading);
        WorkBars bars = new WorkBars(this);
        bars.setRows(rows, color);
        reportBox.addView(bars, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private List<WorkBars.Row> tally(List<WorkOrder> rows, String field) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < rows.size(); i++) {
            WorkOrder order = rows.get(i);
            String key = "phase".equals(field) ? order.phase
                    : "state".equals(field) ? order.state
                    : "trade".equals(field) ? order.trade
                    : "type".equals(field) ? order.woType
                    : order.client;
            if (key.length() == 0) key = "—";
            Integer n = counts.get(key);
            counts.put(key, n == null ? 1 : n + 1);
        }
        List<WorkBars.Row> out = new ArrayList<WorkBars.Row>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) out.add(new WorkBars.Row(e.getKey(), e.getValue()));
        Collections.sort(out, new Comparator<WorkBars.Row>() {
            @Override public int compare(WorkBars.Row a, WorkBars.Row b) { return b.value - a.value; }
        });
        if (out.size() > 8) return new ArrayList<WorkBars.Row>(out.subList(0, 8));
        return out;
    }

    private List<WorkBars.Row> aging(List<WorkOrder> rows, boolean created) {
        int a = 0, b = 0, c = 0, d = 0;
        for (int i = 0; i < rows.size(); i++) {
            int days = WorkOrder.ageDays(created ? rows.get(i).createdAt : rows.get(i).statusChangedAt);
            if (days < 0) continue;
            if (days <= 2) a++;
            else if (days <= 7) b++;
            else if (days <= 14) c++;
            else d++;
        }
        List<WorkBars.Row> out = new ArrayList<WorkBars.Row>();
        out.add(new WorkBars.Row("0–2 days", a));
        out.add(new WorkBars.Row("3–7 days", b));
        out.add(new WorkBars.Row("8–14 days", c));
        out.add(new WorkBars.Row("15+ days", d));
        return out;
    }
}
