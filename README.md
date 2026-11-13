# BareMinimum

> *Does the absolute minimum required for the request to still work.*

BareMinimum is a Burp Suite extension that works out which **cookies**, **headers** and **parameters** a request actually needs. It replays the request with parts removed, compares each response with the original, and gives you the smallest request that still behaves the same — ready for a clean screenshot, a proof of concept, or a report.

It works in both Burp Suite Professional and Burp Suite Community Edition.

## What it does

- **Finds the required items quickly.** Instead of removing items one at a time, BareMinimum uses a binary search (QuickXplain). In the project's tests, finding the 2 required cookies among 30 takes 10–21 requests in total depending on where they sit, and finding 2 among 100 takes 21, where one-at-a-time removal needs more than 100. If nothing is required, it finds that out with a single test request.
- **Handles items that are only required together**, such as a CSRF cookie and its matching header.
- **Covers cookies, headers, URL query parameters, form bodies** (`application/x-www-form-urlencoded` and `multipart/form-data`) **and top-level JSON fields.**
- **Saves each result as a reusable profile** (for example `GET - ALL 1`). A profile records the *names* that were required, so you can apply it to a later request to the same endpoint and keep that request's own, current values.
- **Leaves what it keeps untouched.** Kept cookies, headers and parameters are copied from the original request exactly as they were. Nothing is decoded or re-encoded.

### How it differs from Request Minimizer

The BApp Store already contains [Request Minimizer](https://portswigger.net/bappstore/cc16f37549ff416b990d4312490f5fd1), which has the same basic purpose. What BareMinimum adds:

- **Binary search with whole-category pruning**, so large requests need far fewer test requests.
- **A choice of scope**: cookies, headers, parameters, or all of them.
- **Reusable profiles**: a result is saved by name and can be applied to other requests later.
- **Configurable matching**: status code, body length tolerance, JSON key structure, required and forbidden strings.
- **Safety checks**: the response is confirmed stable before the search starts, and the final request is confirmed at the end.
- **A request editor tab** with an item checklist, a live step log and undo.
- **Built on the Montoya API.**

## Important: this extension sends requests

BareMinimum works by **re-sending the request you choose many times**, each time with parts removed. Only use it on requests that are safe to repeat. Replaying a request that changes state (placing an order, deleting a record, sending a message) will perform that action repeatedly.

All requests are sent through Burp's own HTTP stack, so your upstream proxy, TLS and session handling settings apply. BareMinimum makes no other network connections.

## Using it

### From the context menu

Right-click a request anywhere in Burp (Proxy history, Repeater, Site map, …) and choose **Extensions > BareMinimum**:

| Menu item | What it does |
|---|---|
| **Find required cookies** | Searches the cookies in the `Cookie` header |
| **Find required headers** | Searches the request headers, except pinned ones |
| **Find required parameters** | Searches URL, body and top-level JSON parameters |
| **Find required ALL** | Searches all three together |
| **Minimize Request [profile]** | Applies the active profile to this request |
| *Saved results* | Click a saved profile to make it the active one |

The search runs in the background. Progress and results appear in the **BareMinimum** tab.

**Minimize Request** edits the request in place when you use it in an editable request editor such as Repeater. In read-only views it sends the minimized request to a new Repeater tab instead.

### From the request editor tab

Every request editor has a **BareMinimum** tab next to Pretty / Raw / Hex.

- **Discover** buttons run a search on the request shown. With *Auto-minimize when finished* ticked, the result is applied to the request as soon as the search ends.
- **Saved Profile** and **Minimize Request** apply an existing profile to the request.
- The **checklist** lists every removable item. Untick the ones you do not want and click **Apply Checked Items**.
- **Restore Original** undoes the changes made in this tab.

### The BareMinimum tab

- **Saved Profiles** — rename, delete, set the active profile, see exactly which items each one requires, preview the minimized request, or send it to Repeater.
- **Step log** — every test request with how many items it kept, the status code, the body length and whether it matched the baseline. **Cancel Run** stops the current search.
- **Settings & Baseline Rules** — see below.

## How "still works" is decided

A test response counts as equivalent to the original when it passes every enabled rule:

| Rule | Default |
|---|---|
| Same HTTP status code | On |
| Body length within a tolerance of the original (minimum allowance 16 bytes) | On, 5% |
| Same top-level JSON keys, when the original response is a JSON object | On |
| Response contains a given string | Off |
| Response does not contain a given string (for example `Unauthorized`) | Off |

Other settings:

- **Stability check** (on by default) — sends the original request a second time before searching. If the two responses do not match each other under your rules, the run stops and tells you, because no comparison would be reliable. Loosen the rules or add a required string and try again.
- **Pinned headers** — headers that are never removed. `Host` and `Content-Length` are always pinned.
- **Delay between requests** — for rate-limited targets.

Settings are remembered between Burp sessions. Profiles are kept for the current session only.

At the end of each search BareMinimum sends the minimized request once more to confirm it. If that fails and the original request no longer works either — typically because the session expired during the run — the run is reported as failed and no profile is saved.

## Limitations

- JSON minimization covers the top-level fields of a JSON object. Nested fields, JSON arrays and XML bodies are left alone.
- Request targets with a `#fragment` are not treated specially.
- A response that legitimately varies a lot between identical requests needs looser matching rules, or a required string, before a search can succeed.

## Building

Requires JDK 17 or newer. The Gradle wrapper is included.

```
./gradlew clean test jar
```

On Windows use `gradlew.bat`. The extension is written to `build/libs/BareMinimum-1.0.0.jar`. It has no dependencies beyond the Montoya API, which Burp provides.

## Installing

1. In Burp, open **Extensions > Installed** and click **Add**.
2. Leave **Extension type** as **Java** and select `build/libs/BareMinimum-1.0.0.jar`.

Requires Burp Suite 2023.12 or later.

## How it treats your data

- Request and response content is treated as untrusted. Names and values taken from HTTP messages are always displayed as plain text, never interpreted as HTML.
- Nothing is written to disk except your own settings, stored in Burp's extension preferences. Saved profiles keep one minimized request each, in Burp's temporary storage, for the current session.
- All work that sends requests runs on a background thread, and stops when the extension is unloaded.

## License

Released under the [MIT License](LICENSE).
