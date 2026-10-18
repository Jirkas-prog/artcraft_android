package cz.jirkas.crafthub;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;

final class State {
    private State() {}
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("hub", Context.MODE_PRIVATE); }
    static String repo(Context c) { return prefs(c).getString("repository", Catalog.DEFAULT_REPO); }
    static List<String> pending(Context c) {
        List<String> ids = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("pending", "[]"));
            if (a.length() <= 64) for (int i = 0; i < a.length(); i++) ids.add(a.getString(i));
        } catch (JSONException ignored) { /* A corrupt recovery record must not trigger installs. */ }
        return ids;
    }
    static boolean pending(Context c, List<String> ids) {
        return prefs(c).edit().putString("pending", new JSONArray(ids).toString()).commit();
    }
    static void clearSession(Context c) {
        prefs(c).edit().remove("session").remove("activePackage").remove("activeVersion").commit();
    }
    static void completed(Context c) {
        List<String> remaining = pending(c);
        String id = prefs(c).getString("activePackage", "");
        remaining.remove(id);
        pending(c, remaining);
        clearSession(c);
    }
}
