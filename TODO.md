# TODO

## Custom Cast Receiver app (branded name instead of "Default Media Receiver")

Right now casting launches Google's built-in Default Media Receiver (app ID
`CC1AD845`), whose display name ("Default Media Receiver") is fixed in
Google's Cast app registry — not overridable via LOAD/customData or any
sender-side code change.

To brand it (e.g. show "Mint Mango" on the speaker/display):

1. Register a Cast Receiver app in the Google Cast SDK Developer Console
   (console.cast.google.com) — one-time $5 fee, no review needed for
   personal/unlisted use.
2. Build + host a small Cast Web Receiver page (HTML/JS, Cast Web Receiver
   SDK) on any public HTTPS host (GitHub Pages, Cloudflare Pages, etc.) —
   this is where the branding, name, and background come from.
3. Point `DEFAULT_MEDIA_RECEIVER_APP_ID` in
   `app/src/main/java/com/themoon/y1/cast/CastConnection.java:47` at the new
   app ID and launch that instead of `CC1AD845`.

Mostly a side project (web app + hosting to maintain) rather than an
in-repo change. Revisit if the polish is worth it.
