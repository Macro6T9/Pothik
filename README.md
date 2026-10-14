# পথিক ব্রাউজার (Pothik Browser)

আপনার HTML ডিজাইন + আসল Chromium WebView। Google, Facebook, YouTube সহ সব সাইট সরাসরি খোলে।

## চালানোর নিয়ম
1. Android Studio খুলুন → **File ▸ Open** → এই `Pothik` ফোল্ডার বাছুন
2. Gradle Sync শেষ হওয়া পর্যন্ত অপেক্ষা করুন (ইন্টারনেট লাগবে)
3. ফোনে USB debugging চালু করে **Run ▶** চাপুন
4. APK ফাইল চাইলে: **Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)**

## ফাইল কোথায়
- `app/src/main/assets/index.html` — আপনার UI (এখানেই ডিজাইন বদলান)
- `app/src/main/java/com/pothik/browser/MainActivity.kt` — WebView ট্যাব, ডাউনলোড, ফুলস্ক্রিন ভিডিও, আপলোড
- `app/src/main/java/com/pothik/browser/PassThroughWebView.kt` — UI-র নিচে সাইট WebView-তে ছোঁয়া পাঠায়

## সীমাবদ্ধতা
- প্রাইভেট মোডে ক্যাশ বন্ধ থাকে, কিন্তু কুকি/লগইন আলাদা থাকে না।
- জুম টেক্সট সাইজ বদলায় (পিঞ্চ জুমও কাজ করে)।
- `window.open` পপআপ একই ট্যাবে খোলে।
