package com.tvdash.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** View-only map of open work. The remote browses dots. Nothing on this screen changes a job. */
public class WorkActivity extends Activity {
    private WorkMapView map;
    private TextView cardTitle;
    private TextView cardBody;
    private LinearLayout legend;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        List<WorkClient.Job> jobs = WorkClient.load(new Prefs(this).workBearer());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int side = Ui.dp(this, 28);
        root.setPadding(side, Ui.dp(this, 18), side, Ui.dp(this, 16));

        TextView title = Ui.text(this, "WORK", 13, Ui.ACCENT, false);
        title.setPadding(0, 0, 0, Ui.dp(this, 8));
        root.addView(title);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(stat("OPEN", String.valueOf(WorkClient.openCount(jobs))), statLp());
        stats.addView(stat("HOT", String.valueOf(WorkClient.hotCount(jobs))), statLp());
        stats.addView(stat("OVERDUE", String.valueOf(WorkClient.overdueCount(jobs))), statLp());
        stats.addView(stat("BY STATE", WorkClient.topStates(jobs)), statLp());
        root.addView(stats);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, Ui.dp(this, 12), 0, 0);

        map = new WorkMapView(this);
        map.setJobs(jobs);
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        body.addView(map, mapLp);

        legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.VERTICAL);
        legend.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), 0, 0);
        TextView legendTitle = Ui.text(this, "TRADE", 11, Ui.TEXT_3, false);
        legendTitle.setPadding(0, 0, 0, Ui.dp(this, 8));
        legend.addView(legendTitle);
        for (String trade : WorkClient.legend(jobs)) legend.addView(legendRow(trade));
        TextView hotKey = Ui.text(this, "Ring  hot", 11, WorkClient.CORAL, false);
        hotKey.setPadding(0, Ui.dp(this, 10), 0, 0);
        legend.addView(hotKey);
        body.addView(legend, new LinearLayout.LayoutParams(Ui.dp(this, 210), ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.glass(this));
        int pad = Ui.dp(this, 14);
        card.setPadding(pad, pad, pad, pad);
        cardTitle = Ui.text(this, "", 18, Color.WHITE, true);
        cardBody = Ui.text(this, "", 14, Ui.TEXT_2, false);
        cardBody.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(cardTitle);
        card.addView(cardBody);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = Ui.dp(this, 10);
        root.addView(card, cardLp);

        map.setListener(new WorkMapView.Select() {
            @Override public void onJob(WorkClient.Job job) {
                show(job);
            }
        });

        setContentView(root);
        map.post(new Runnable() {
            @Override public void run() {
                map.requestFocus();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.startAmbient();
    }

    @Override
    protected void onPause() {
        if (map != null) map.stopAmbient();
        super.onPause();
    }

    private LinearLayout.LayoutParams statLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(Ui.dp(this, 10));
        return lp;
    }

    private View stat(String label, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.glass(this));
        int p = Ui.dp(this, 12);
        box.setPadding(p, p, p, p);
        TextView k = Ui.text(this, label, 11, Ui.TEXT_3, false);
        TextView v = Ui.text(this, value, 26, Color.WHITE, false);
        v.setPadding(0, Ui.dp(this, 2), 0, 0);
        box.addView(k);
        box.addView(v);
        return box;
    }

    private View legendRow(String trade) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        View swatch = new View(this);
        swatch.setBackgroundColor(WorkClient.color(trade));
        int s = Ui.dp(this, 10);
        row.addView(swatch, new LinearLayout.LayoutParams(s, s));
        TextView name = Ui.text(this, trade, 12, Ui.TEXT_2, false);
        name.setPadding(Ui.dp(this, 8), 0, 0, 0);
        row.addView(name);
        return row;
    }

    private void show(WorkClient.Job job) {
        if (cardTitle == null) return;
        if (job == null) {
            cardTitle.setText("No open work");
            cardBody.setText("View only");
            return;
        }
        cardTitle.setText(job.trade);
        cardTitle.setTextColor(WorkClient.color(job.trade));
        StringBuilder b = new StringBuilder();
        b.append(job.id).append("   ").append(job.city).append(", ").append(job.state);
        b.append("\n").append(job.phase);
        if (job.hot) b.append("   ·   Hot");
        if (job.overdue) b.append("   ·   Overdue");
        b.append("\nView only");
        cardBody.setText(b.toString());
    }
}
