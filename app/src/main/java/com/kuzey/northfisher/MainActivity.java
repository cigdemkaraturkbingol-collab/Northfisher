package com.kuzey.northfisher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ = 71;
    private static final int BG = Color.rgb(7,20,32);
    private static final int CARD = Color.rgb(15,37,53);
    private static final int CARD2 = Color.rgb(20,49,67);
    private static final int TEXT = Color.rgb(242,249,255);
    private static final int MUTED = Color.rgb(155,180,196);
    private static final int BLUE = Color.rgb(70,190,255);
    private static final int GREEN = Color.rgb(91,214,155);
    private static final int ORANGE = Color.rgb(255,183,77);
    private static final int RED = Color.rgb(255,102,102);

    private LinearLayout content;
    private TextView locationText, updatedText;
    private ProgressBar progress;
    private Button refresh;
    private LocationManager lm;
    private Location currentLocation;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        lm = (LocationManager)getSystemService(LOCATION_SERVICE);
        setContentView(buildUi());
        requestAndLoad();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(18),dp(16),dp(8));
        root.setBackgroundColor(BG);

        TextView title = tv("Northfisher",28,TEXT,Typeface.BOLD);
        root.addView(title);
        root.addView(tv("Hava • Deniz • Balıkçı • Yol",13,MUTED,Typeface.NORMAL));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,dp(16),0,dp(10));
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        locationText = tv("Konum alınıyor…",15,TEXT,Typeface.BOLD);
        updatedText = tv("",11,MUTED,Typeface.NORMAL);
        l.addView(locationText); l.addView(updatedText);
        row.addView(l,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        refresh = button("↻"); refresh.setOnClickListener(v -> requestAndLoad()); row.addView(refresh);
        root.addView(row);

        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));

        ScrollView s = new ScrollView(this); s.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0,dp(12),0,dp(24));
        s.addView(content); root.addView(s,new LinearLayout.LayoutParams(-1,0,1));

        TextView footer = tv("Gerçek veri: Open‑Meteo • API anahtarı gerekmez",10,MUTED,Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER); footer.setPadding(0,dp(5),0,dp(5)); root.addView(footer);
        return root;
    }

    private void requestAndLoad() {
        progress.setVisibility(View.VISIBLE); refresh.setEnabled(false);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ);
            return;
        }
        getLocation();
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r==REQ) {
            if (g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) getLocation();
            else { progress.setVisibility(View.GONE); refresh.setEnabled(true); showError("Konum izni gerekli","Uygulama bulunduğun yere göre hava ve deniz verisi gösterebilmek için konum izni ister."); }
        }
    }

    @SuppressWarnings("MissingPermission") private void getLocation() {
        Location a=null,b=null;
        try { a=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); b=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch(Exception ignored) {}
        Location best = a!=null && (b==null || a.getTime()>=b.getTime()) ? a : b;
        if (best!=null) { currentLocation=best; load(best); return; }
        try {
            String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)?LocationManager.NETWORK_PROVIDER:LocationManager.GPS_PROVIDER;
            lm.requestSingleUpdate(provider,new LocationListener(){
                @Override public void onLocationChanged(Location loc){ try{lm.removeUpdates(this);}catch(Exception ignored){} currentLocation=loc; load(loc); }
                @Override public void onProviderEnabled(String p){} @Override public void onProviderDisabled(String p){} @Override public void onStatusChanged(String p,int s,Bundle e){}
            },null);
        } catch(Exception e) { progress.setVisibility(View.GONE); refresh.setEnabled(true); showError("Konum alınamadı","Cihaz konumunu açıp tekrar dene."); }
    }

    private void load(Location loc) {
        final double lat=loc.getLatitude(), lon=loc.getLongitude();
        locationText.setText(String.format(Locale.getDefault(),"%.4f, %.4f",lat,lon));
        updatedText.setText("Veriler yenileniyor…"); content.removeAllViews();
        pool.execute(() -> {
            try {
                JSONObject weather = json(weatherUrl(lat,lon));
                JSONObject marine = null;
                try { marine=json(marineUrl(lat,lon)); } catch(Exception ignored) {}
                String place = place(lat,lon);
                JSONObject finalMarine=marine; String finalPlace=place;
                runOnUiThread(() -> render(finalPlace,weather,finalMarine));
            } catch(Exception e) { runOnUiThread(() -> { progress.setVisibility(View.GONE); refresh.setEnabled(true); showError("Veri alınamadı","İnternet bağlantını kontrol edip tekrar dene.\n"+e.getMessage()); }); }
        });
    }

    private void render(String place,JSONObject weather,JSONObject marine) {
        progress.setVisibility(View.GONE); refresh.setEnabled(true); content.removeAllViews();
        if (place!=null && !place.isEmpty()) locationText.setText(place);
        updatedText.setText("Güncellendi: "+new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()));
        try {
            JSONObject c=weather.getJSONObject("current");
            double temp=c.optDouble("temperature_2m",Double.NaN), feels=c.optDouble("apparent_temperature",Double.NaN), wind=c.optDouble("wind_speed_10m",Double.NaN), gust=c.optDouble("wind_gusts_10m",Double.NaN), rain=c.optDouble("precipitation",0), snow=c.optDouble("snowfall",0), hum=c.optDouble("relative_humidity_2m",Double.NaN), pressure=c.optDouble("surface_pressure",Double.NaN), visibility=c.optDouble("visibility",Double.NaN);
            int code=c.optInt("weather_code",-1);

            LinearLayout hero=card(CARD2); hero.addView(tv(emoji(code)+"  "+fmt(temp,"°C"),34,TEXT,Typeface.BOLD)); hero.addView(tv(weatherText(code)+" • Hissedilen "+fmt(feels,"°C"),14,MUTED,Typeface.NORMAL));
            LinearLayout metrics=new LinearLayout(this); metrics.setOrientation(LinearLayout.HORIZONTAL); metrics.setPadding(0,dp(12),0,0);
            metrics.addView(metric("Rüzgâr",fmt(wind," km/sa")),new LinearLayout.LayoutParams(0,-2,1)); metrics.addView(metric("Nem",fmt(hum,"%")),new LinearLayout.LayoutParams(0,-2,1)); metrics.addView(metric("Basınç",fmt(pressure," hPa")),new LinearLayout.LayoutParams(0,-2,1)); hero.addView(metrics); add(hero);

            double wave=Double.NaN, period=Double.NaN, waveDir=Double.NaN, seaTemp=Double.NaN, current=Double.NaN, currentDir=Double.NaN;
            if (marine!=null && marine.optJSONObject("current")!=null) { JSONObject m=marine.optJSONObject("current"); wave=m.optDouble("wave_height",Double.NaN); period=m.optDouble("wave_period",Double.NaN); waveDir=m.optDouble("wave_direction",Double.NaN); seaTemp=m.optDouble("sea_surface_temperature",Double.NaN); current=m.optDouble("ocean_current_velocity",Double.NaN); currentDir=m.optDouble("ocean_current_direction",Double.NaN); }
            LinearLayout sea=card(CARD); sea.addView(section("🌊 Deniz durumu"));
            if (marine==null) sea.addView(tv("Bu konuma ait deniz verisi bulunamadı.",14,MUTED,Typeface.NORMAL));
            else { sea.addView(line("Dalga yüksekliği",fmt(wave," m"))); sea.addView(line("Dalga periyodu",fmt(period," sn"))); sea.addView(line("Dalga yönü",dir(waveDir))); sea.addView(line("Deniz sıcaklığı",fmt(seaTemp,"°C"))); sea.addView(line("Akıntı",Double.isNaN(current)?"Veri yok":String.format(Locale.getDefault(),"%.1f km/sa • %s",current,dir(currentDir)))); }
            sea.addView(tv("⚠ Deniz verileri tahmindir; can güvenliği ve seyir için resmî denizcilik uyarılarını da kontrol et.",11,ORANGE,Typeface.NORMAL)); add(sea);

            int fs=fishing(wind,gust,rain,wave,pressure); LinearLayout fish=card(CARD); fish.addView(section("🎣 Balıkçı görünümü")); fish.addView(tv(fs+"/100 • "+(fs>=70?"Uygun":fs>=45?"Orta":"Zayıf"),24,fs>=70?GREEN:fs>=45?ORANGE:RED,Typeface.BOLD)); fish.addView(tv(fishAdvice(wind,gust,rain,wave),13,MUTED,Typeface.NORMAL)); fish.addView(tv("Puan meteorolojik koşullardan üretilir; balık varlığını garanti etmez.",10,MUTED,Typeface.NORMAL)); add(fish);

            int rr=roadRisk(temp,rain,snow,wind,gust,visibility); LinearLayout road=card(CARD); road.addView(section("🚗 Yol durumu")); road.addView(tv(rr>=70?"⛔ Yüksek hava riski":rr>=40?"⚠ Dikkatli sürüş":"✅ Hava kaynaklı risk düşük",21,rr>=70?RED:rr>=40?ORANGE:GREEN,Typeface.BOLD)); road.addView(line("Yağış",String.format(Locale.getDefault(),"%.1f mm",rain))); road.addView(line("Kar",String.format(Locale.getDefault(),"%.1f cm",snow))); road.addView(line("Görüş",Double.isNaN(visibility)?"Veri yok":String.format(Locale.getDefault(),"%.1f km",visibility/1000d))); road.addView(line("Rüzgâr",fmt(wind," km/sa"))); road.addView(line("Hamle",fmt(gust," km/sa"))); road.addView(tv(roadAdvice(rr,temp,rain,snow,gust,visibility),13,MUTED,Typeface.NORMAL)); road.addView(tv("Bu bölüm gerçek meteorolojik veriden yol riskini değerlendirir; trafik yoğunluğu vermez.",10,MUTED,Typeface.NORMAL)); add(road);

            addForecast(weather);
        } catch(Exception e) { showError("Görüntüleme hatası",e.getMessage()); }
    }

    private void addForecast(JSONObject w) {
        JSONObject d=w.optJSONObject("daily"); if(d==null)return; JSONArray dates=d.optJSONArray("time"),max=d.optJSONArray("temperature_2m_max"),min=d.optJSONArray("temperature_2m_min"),rain=d.optJSONArray("precipitation_probability_max"),wind=d.optJSONArray("wind_speed_10m_max"),codes=d.optJSONArray("weather_code"); if(dates==null)return;
        LinearLayout box=card(CARD); box.addView(section("📅 7 günlük tahmin"));
        for(int i=0;i<Math.min(7,dates.length());i++){ String v=String.format(Locale.getDefault(),"%s  %.0f° / %.0f°   🌧 %d%%   💨 %.0f",emoji(codes!=null?codes.optInt(i,-1):-1),max!=null?max.optDouble(i):0,min!=null?min.optDouble(i):0,rain!=null?rain.optInt(i):0,wind!=null?wind.optDouble(i):0); box.addView(line(day(dates.optString(i)),v)); }
        add(box);
    }

    private String weatherUrl(double lat,double lon){ return "https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,snowfall,weather_code,surface_pressure,wind_speed_10m,wind_gusts_10m,visibility&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max&timezone=auto&forecast_days=7"; }
    private String marineUrl(double lat,double lon){ return "https://marine-api.open-meteo.com/v1/marine?latitude="+lat+"&longitude="+lon+"&current=wave_height,wave_direction,wave_period,sea_surface_temperature,ocean_current_velocity,ocean_current_direction&timezone=auto&cell_selection=sea"; }

    private JSONObject json(String u) throws Exception { HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection(); c.setConnectTimeout(12000); c.setReadTimeout(12000); c.setRequestProperty("User-Agent","Northfisher/1.1 Android"); int code=c.getResponseCode(); InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream(); BufferedReader b=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8)); StringBuilder s=new StringBuilder(); String line; while((line=b.readLine())!=null)s.append(line); b.close(); c.disconnect(); if(code<200||code>=300)throw new Exception("HTTP "+code); return new JSONObject(s.toString()); }
    private String place(double lat,double lon){ try{ List<Address> a=new Geocoder(this,new Locale("tr","TR")).getFromLocation(lat,lon,1); if(a!=null&&!a.isEmpty()){ Address x=a.get(0); String l=x.getLocality(),d=x.getSubAdminArea(),city=x.getAdminArea(); if(l!=null&&city!=null)return l+", "+city; if(d!=null&&city!=null)return d+", "+city; if(city!=null)return city; }}catch(Exception ignored){} return String.format(Locale.getDefault(),"%.4f, %.4f",lat,lon); }

    private int fishing(double wind,double gust,double rain,double wave,double pressure){ int s=100; if(!Double.isNaN(wind))s-=Math.max(0,(int)((wind-15)*1.2)); if(!Double.isNaN(gust)&&gust>45)s-=15; if(rain>3)s-=15; if(!Double.isNaN(wave)){ if(wave>1)s-=20; if(wave>2)s-=25; } if(!Double.isNaN(pressure)&&(pressure<995||pressure>1030))s-=10; return Math.max(0,Math.min(100,s)); }
    private String fishAdvice(double wind,double gust,double rain,double wave){ if(!Double.isNaN(wave)&&wave>2)return "Dalga çok yüksek; kıyı ve tekne güvenliği açısından uygun görünmüyor."; if(gust>60)return "Kuvvetli rüzgâr hamleleri var; denize çıkmak riskli olabilir."; if(rain>5)return "Kuvvetli yağış bekleniyor. Görüş ve zemin koşullarına dikkat."; if(wind>35)return "Rüzgâr belirgin; korunaklı kıyı noktaları daha uygun olabilir."; return "Meteorolojik koşullar balıkçılık açısından genel olarak elverişli görünüyor."; }
    private int roadRisk(double temp,double rain,double snow,double wind,double gust,double vis){ int r=0; if(rain>=0.2)r+=15; if(rain>=2)r+=20; if(snow>0)r+=35; if(temp<=1&&(rain>0||snow>0))r+=35; if(wind>=40)r+=15; if(gust>=65)r+=25; if(!Double.isNaN(vis)&&vis<3000)r+=20; if(!Double.isNaN(vis)&&vis<1000)r+=25; return Math.min(100,r); }
    private String roadAdvice(int r,double temp,double rain,double snow,double gust,double vis){ if(temp<=1&&(rain>0||snow>0))return "Buzlanma ihtimali var; hızı düşür ve takip mesafesini artır."; if(snow>0)return "Kar nedeniyle yol yüzeyi kaygan olabilir."; if(!Double.isNaN(vis)&&vis<1000)return "Görüş çok düşük; yavaşla ve uygun aydınlatmayı kullan."; if(gust>=65)return "Kuvvetli yan rüzgâr özellikle açık yollar ve köprülerde risk oluşturabilir."; if(rain>=2)return "Kuvvetli yağış var; su birikintisi ve kızaklama riski artabilir."; return r>=40?"Meteorolojik koşullar sürüşü etkileyebilir.":"Belirgin bir meteorolojik yol riski görünmüyor."; }

    private String emoji(int c){ if(c==0)return "☀️"; if(c<=3)return "⛅"; if(c==45||c==48)return "🌫️"; if(c>=51&&c<=67)return "🌧️"; if(c>=71&&c<=77)return "🌨️"; if(c>=80&&c<=82)return "🌦️"; if(c>=95)return "⛈️"; return "🌤️"; }
    private String weatherText(int c){ if(c==0)return "Açık"; if(c<=3)return "Parçalı bulutlu"; if(c==45||c==48)return "Sisli"; if(c>=51&&c<=67)return "Yağmurlu"; if(c>=71&&c<=77)return "Karlı"; if(c>=80&&c<=82)return "Sağanak"; if(c>=95)return "Gök gürültülü"; return "Hava durumu"; }
    private String dir(double d){ if(Double.isNaN(d))return "Veri yok"; String[] n={"K","KD","D","GD","G","GB","B","KB"}; int i=(int)Math.round((((d%360)+360)%360)/45.0)%8; return n[i]+String.format(Locale.getDefault()," (%.0f°)",d); }
    private String fmt(double v,String u){ return Double.isNaN(v)?"Veri yok":String.format(Locale.getDefault(),Math.abs(v)>=100?"%.0f%s":"%.1f%s",v,u); }
    private String day(String s){ try{ Date d=new SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(s); return new SimpleDateFormat("EEE d MMM",new Locale("tr","TR")).format(d); }catch(Exception e){return s;} }

    private LinearLayout card(int color){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(16),dp(15),dp(16),dp(15)); GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(18)); l.setBackground(g); return l; }
    private void add(View v){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(12)); content.addView(v,p); }
    private TextView section(String s){ TextView t=tv(s,18,TEXT,Typeface.BOLD); t.setPadding(0,0,0,dp(9)); return t; }
    private TextView line(String a,String b){ TextView t=tv(a+"   •   "+b,13,TEXT,Typeface.NORMAL); t.setPadding(0,dp(4),0,dp(4)); return t; }
    private View metric(String a,String b){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.addView(tv(b,14,TEXT,Typeface.BOLD)); l.addView(tv(a,10,MUTED,Typeface.NORMAL)); return l; }
    private TextView tv(String s,int sp,int color,int style){ TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(Typeface.create(Typeface.DEFAULT,style)); return t; }
    private Button button(String s){ Button b=new Button(this); b.setText(s); b.setTextSize(20); b.setTextColor(TEXT); GradientDrawable g=new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(14)); b.setBackground(g); b.setMinWidth(dp(54)); return b; }
    private void showError(String title,String msg){ content.removeAllViews(); LinearLayout c=card(CARD); c.addView(tv(title,20,RED,Typeface.BOLD)); c.addView(tv(msg==null?"Bilinmeyen hata":msg,13,MUTED,Typeface.NORMAL)); add(c); }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
}
