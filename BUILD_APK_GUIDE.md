# 📱 How to Turn LANVOX Into an APK — 100% Online (No Android Studio)

Your phone can install a **Debug APK** directly. The easiest free way is
**GitHub Actions** — it builds the APK for you on GitHub's servers, and you
download the finished file. Everything below can be done from a phone or a
computer browser.

---

## ✅ Recommended: GitHub Actions (free, ~5–10 minutes)

### Step 1 — Get a GitHub account
Go to **https://github.com** and sign up (free).

### Step 2 — Create a repository
1. Click the **+** icon (top right) → **New repository**.
2. Name it anything, e.g. `lanvox`.
3. Choose **Public** (required for free unlimited builds).
4. Click **Create repository**.

### Step 3 — Upload the project
1. On the new repository page, click **"uploading an existing file"** link.
2. Extract `LANVOX_v4.0.zip` on your device first.
3. Drag & drop **everything inside the extracted folder** — including the
   hidden `.github` folder, `app`, `gradle`, all `.kts` files, etc.
   *(On Android, enable "Show hidden files" in your file manager to see `.github`.)*
4. Wait for all files to upload → click **Commit changes**.

> The build starts **automatically** when the upload finishes (that's what
> the included `.github/workflows/build-apk.yml` file does).

### Step 4 — Download your APK
1. Open the **Actions** tab on your repository.
2. You'll see a workflow run called **"Build LANVOX APK"** — click it
   (yellow dot = building, green ✓ = done, ~5–10 min).
3. Scroll to the **Artifacts** section → download **`LANVOX-debug-APK`**.
4. Unzip it — inside is **`app-debug.apk`**.

### Step 5 — Install on your phone
1. Copy `app-debug.apk` to both phones (WhatsApp, Bluetooth, USB — anything).
2. Tap it → Android will ask to allow **"Install unknown apps"** → allow.
3. Install → open LANVOX. Done! ✨

> 🔁 Every time you push new code, a fresh APK is built automatically.
> You can also trigger a build manually: **Actions → Build LANVOX APK → Run workflow**.

---

## 🔄 Other platforms that compile existing Kotlin projects

| Platform | Free tier | Notes |
|----------|-----------|-------|
| **GitHub Actions** | Unlimited (public repos) | ⭐ What this project is pre-configured for |
| **GitHub Codespaces** | ~60 hrs/month | Browser terminal → run `gradle assembleDebug` |
| **GitLab CI** | 400 min/month | Same idea as GitHub Actions |
| **Codemagic** | ~500 min/month | Mobile-focused CI/CD |
| **Bitrise** | Limited free credits | Mobile-focused CI/CD |
| **Google AI Studio / Antigravity** | Free | Google's browser dev tools (Project IDX / Firebase Studio is being retired by March 2027) |

## ❌ What will NOT work

- **No-code app makers** (AppsGeyser, Appy Pie, "APK Builder" websites…) —
  they only generate their own template apps. They **cannot compile** an
  existing Android Studio / Kotlin project like LANVOX.

---

## 📝 Debug APK vs Play Store

The APK from this workflow is a **debug APK** — perfectly fine for installing
on phones, sharing with friends, and daily use. If you ever want to publish
LANVOX on **Google Play**, you'll need a signed **release** build (a `.aab`
file) with your own keystore — ask me and I'll set that up too.
