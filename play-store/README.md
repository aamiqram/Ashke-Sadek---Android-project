# Play Store publishing checklist — এশকে ছাদেক (Ashke Shadek)

Everything here happens in a **browser** by you (the account owner). No secret is
stored in this repo; the release keystore and its password stay on your disk
(`C:\Projects\AAMI\Ashke-Sadek-Hybrid\keystore\ashke-shadek-release.jks`,
password backup sits beside it — never paste it into chat or a form you do not
trust).

## 1. Play Console account (one-time, ~$25)
- [ ] Create the account at https://play.google.com/console (one-time $25 fee, card/2FA).
- [ ] Choose **Organization** if you have one — personal accounts created after
      Nov 13, 2023 must run a **closed test with 20 opted-in testers for 14
      continuous days** before production access is granted. (Older/personal
      accounts: follow whatever Play Console shows on your dashboard.)
- [ ] Complete identity verification and enable 2FA on the Google account.

## 2. Signing — the one decision that must be right
- [ ] When Play asks about app signing on your first AAB upload, choose the
      option to **keep using the same key as the upload key** (our CI already
      signs the AAB with the permanent `CN=Ashke Shadek` release key —
      SHA-256 `b55007c8…93f6a`). Google then stores that key as the app
      signing key. Do **not** let Google generate a new key.
- [ ] Losing the keystore after the first release means you can never update
      the app again. The backup beside the keystore is the recovery path.

## 3. Artifacts — what to download from GitHub Actions
Every run of **Actions → Build Android APK** (workflow `build-apk.yml`) now
produces, when signing is configured:
- `ashke-shadek-signed-release-aab` — **this is the Play Store file** (`app-release.aab`).
- `ashke-shadek-signed-release-apk` — for direct distribution/self-update QA.

Upload the **AAB** to Play. Each dispatch must use a higher `appVersionCode`
than the previous upload (the workflow asks for it; CI auto-increments are not
automatic here).

## 4. Store listing
- Copy the title/descriptions from `listing.md` (Bengali primary, English fallback).
- Category: **Books & Reference**. Tags: Books, Reference.
- Upload `app-icon-512.png` (512×512, verified) as the app icon and
  `feature-graphic.png` (1024×500, verified) as the feature graphic.
- Screenshots: 4–8, phone (9:16 or 16:9). Capture: home screen, index screen,
  a song reader page, the voice-search dialog with live candidates.
  For the voice dialog, type a partial lyric first so candidates are visible.

## 5. Privacy policy
- [ ] Put `privacy-policy.html` online at
      https://aamiqram.github.io/Ashke-Sadek---Android-project/privacy-policy.html
      (commit it, then Settings → Pages → deploy from `main` / root, or `/docs`).
- [ ] Paste that URL into Play Console → App content → Privacy policy.

## 6. App content questionnaire (be consistent with the policy)
- Ads: none. In-app purchases: none. Content rating: complete the IARC
  questionnaire (no violence/user content issues → Everyone).
- Target audience: 18+ (avoid designed-for-families extra review burden).
- Data safety: answer **"No data is collected, shared, or is it secure data
  practices required"** — microphone audio is processed entirely on-device and
  never leaves the phone; there are no ads, analytics, or third-party SDKs.
  Internet permission exists only to check/download app updates from this
  project's own GitHub repository (see `privacy-policy.html`).

## 7. Known review risk — read before first upload
The current AAB still declares `REQUEST_INSTALL_PACKAGES` because the
self-updater installs APKs it downloads. Google restricts that permission and a
self-updating app on Play commonly gets flagged in review. Plan (tracked in
BUILD_STATUS): a **`play` flavor** without the self-updater permission for Play
builds, while GitHub/WhatsApp distribution keeps the full flavor. If your first
closed-test upload triggers a policy email about this permission, that is the
reason — the play flavor is the fix, not an appeal.

## 8. Rollout path
1. Upload the signed AAB to **Internal testing** first (free, fast review).
2. Then create **Closed testing**, add 20 tester emails, publish the release,
   and start the 14-day window (personal accounts created after Nov 13, 2023).
3. After the window and the production-access form are approved, promote the
   same AAB to **Production** (staged rollout, e.g. 20% → 100%).

## 9. After publishing
- Play updates are uploaded as new AABs with higher `versionCode`; the in-app
  self-updater only handles GitHub-distributed installs.
- Regenerate `app/src/main/assets/hotwords.txt` with
  `python tools/generate_hotwords.py` whenever the catalogue changes, so the
  offline voice decoder keeps the book's vocabulary.
