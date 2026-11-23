# BareMinimum

> *Does the absolute minimum required for the request to still work.*

BareMinimum is a Burp Suite extension that works out which **cookies**, **headers** and **parameters** a request actually needs. It replays the request with parts removed, compares each response with the original, and gives you the smallest request that still behaves the same — ready for a clean screenshot, a proof of concept, or a report.

It works in both Burp Suite Professional and Burp Suite Community Edition.

## What it does

- **Finds the required items quickly.** Instead of removing items one at a time, BareMinimum removes whole groups and narrows down only the groups that turn out to matter (adaptive group testing). It adjusts the group size to how many required items it has found so far, so it is fast when most items are junk and stays close to one-at-a-time cost when most are required. See [Performance](#performance).
- **Sends independent tests in parallel** (4 at a time by default), so a search takes a fraction of the waiting time.
- **Handles items that only work together** (a CSRF cookie and its matching header) **and alternatives** (either of two session cookies is enough).
- **Covers cookies, headers, URL query parameters, form bodies** (`application/x-www-form-urlencoded` and `multipart/form-data`), **JSON bodies at any depth** (object members and array elements) **and XML bodies** (elements and attributes, for example SOAP).
- **Two ways to judge a response**: simple rules (status code, body length, JSON keys, required and forbidden strings), or **Burp's own response analysis**, which learns which response attributes stay the same and ignores the parts that change on every request.
- **Saves each result as a reusable profile** (for example `GET - ALL 1`). A profile records the *names* that were required, so you can apply it to a later request to the same endpoint and keep that request's own, current values.
- **Leaves what it keeps untouched.** Kept cookies, headers and parameters are copied from the original request exactly as they were. Nothing is decoded or re-encoded.

### How it differs from Request Minimizer

The BApp Store already contains [Request Minimizer](https://portswigger.net/bappstore/cc16f37549ff416b990d4312490f5fd1), which has the same basic purpose. What BareMinimum adds:

- **Speed.** Request Minimizer removes items one at a time, sending one request after another. BareMinimum needs far fewer requests when most items are junk, and sends independent tests in parallel. On a request with 40 cookies of which 2 matter, that is about 18 requests in 6 round trips instead of 42 in 42.
- **Nested JSON and XML bodies are searched the same fast way.** Request Minimizer removes nested keys one at a time; BareMinimum tests nested members in groups like everything else, and removing a container takes everything inside it in a single request.
- **Bodies keep their original form.** Request Minimizer re-serializes the whole body (re-indented JSON, XML rewritten by a converter), and rebuilds the request from the original headers, which discards the cookie, header and URL parameter removals it has just made. BareMinimum copies everything it keeps byte-for-byte, including XML comments, namespace declarations and attribute order.
- **Response analysis plus your own rules.** Both tools can use Burp's response analysis; BareMinimum learns from three responses instead of two, so an attribute that matched by chance is less likely to be treated as fixed, and still applies your status code and required/forbidden string rules on top. Rule-based matching is available as well.
- **Single JAR.** No Jython setup and no extra Python packages to install.
- **A choice of scope**: cookies, headers, parameters, or all of them.
- **Reusable profiles**: a result is saved by name and can be applied to other requests later.
- **Final confirmation**: the minimized request is re-sent at the end, so an expired session cannot produce a wrong result.
- **Progress, cancel and undo**: a live step log, a **Cancel Run** button, and a request editor tab with an item checklist and **Restore Original**.
- **Works from anywhere**, including Proxy history and the site map, not just editable request editors.

## Important: this extension sends requests

BareMinimum works by **re-sending the request you choose many times**, each time with parts removed. Only use it on requests that are safe to repeat. Replaying a request that changes state (placing an order, deleting a record, sending a message) will perform that action repeatedly.

By default up to 4 test requests are sent at the same time. Set **Parallel requests** to 1 for fragile or rate-limited targets; setting a **Delay between requests** also switches to one at a time.

All requests are sent through Burp's own HTTP stack, so your upstream proxy, TLS and session handling settings apply. BareMinimum makes no other network connections.

## Using it

### From the context menu

Right-click a request anywhere in Burp (Proxy history, Repeater, Site map, …) and choose **Extensions > BareMinimum**:

| Menu item | What it does |
|---|---|
| **Find required cookies** | Searches the cookies in the `Cookie` header |
| **Find required headers** | Searches the request headers, except pinned ones |
| **Find required parameters** | Searches URL parameters, form fields, and JSON and XML body items |
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
- **Step log** — every test request with its round, how many items it kept, the status code, the body length and whether it matched the baseline. Requests in the same round were sent together. **Cancel Run** stops the current search.
- **Settings & Baseline Rules** — see below.

## How "still works" is decided

**Response matching** offers two modes.

**Rules** (the default). A test response counts as equivalent to the original when it passes every enabled rule:

| Rule | Default |
|---|---|
| Same HTTP status code | On |
| Body length within a tolerance of the original (minimum allowance 16 bytes) | On, 5% |
| Same top-level JSON keys, when the original response is a JSON object | On |
| Response contains a given string | Off |
| Response does not contain a given string (for example `Unauthorized`) | Off |

**Burp's response analysis.** The original request is sent 3 times. Burp computes a set of attributes for each response: status code, content type, page title, word and line counts, visible text, tag and cookie names, and more. The attributes that came out the same all 3 times must also be the same in every test response; those that changed (a timestamp, a nonce, a CSRF token) are ignored. `Last-Modified` is always ignored, as in Burp's own tools. The status code and required/forbidden string rules still apply. The body length and JSON key rules do not, and the stability check is built in. Use this mode for pages that change on every request.

Other settings:

- **Stability check** (rules mode, on by default) — sends the original request a second time before searching. If the two responses do not match each other under your rules, the run stops and tells you, because no comparison would be reliable. Loosen the rules, add a required string, or switch to Burp's response analysis, and try again.
- **Pinned headers** — headers that are never removed. `Host`, `Content-Length`, and HTTP/2 pseudo-headers (`:authority`, `:path`, `:method`, `:scheme`) are always pinned.
- **Delay between requests** — for rate-limited targets. Any delay means requests go one at a time.
- **Parallel requests** (default 4, up to 16) — how many test requests may be in flight at once. 1 sends them one after another.

Settings are remembered between Burp sessions. Profiles are kept for the current session only.

At the end of each search BareMinimum sends the minimized request once more to confirm it. If that fails and the original request no longer works either — typically because the session expired during the run — the run is reported as failed and no profile is saved.

## Performance

Each test request removes a group of items and asks whether anything in that group is needed. A group that comes back clean is dropped in one go; a group that breaks the request is split and narrowed down. This is Hwang's generalised binary splitting, with the group size chosen from how dense the required items have been so far. With *P* parallel requests, a group is split into *P* + 1 parts and *P* of them are tested at once, so each round narrows it down *P* + 1 times instead of twice.

Nested JSON and XML members are searched the same way. Removing a container also removes everything inside it, so once a container is known to be junk, its contents are dropped without sending any more requests.

Request counts include the baseline, the stability check and the final confirmation. A *round* is one batch of requests sent together, so rounds are what you wait for. Removing items one at a time costs 2 + *n* requests, sent one after another.

| Items | Required | 1 at a time: requests | 4 in parallel: requests | 4 in parallel: rounds | One at a time (requests = rounds) |
|---:|---:|---:|---:|---:|---:|
| 20 | 0 | 3 | 3 | 1 | 22 |
| 20 | 2 | 13 | 14 | 5 | 22 |
| 40 | 1 | 11 | 14 | 5 | 42 |
| 40 | 2 | 16 | 18 | 6 | 42 |
| 40 | 4 | 25 | 26 | 8 | 42 |
| 40 | 10 | 41 | 41 | 12 | 42 |
| 40 | 40 | 48 | 48 | 13 | 42 |
| 150 | 2 | 20 | 24 | 8 | 152 |
| 150 | 8 | 52 | 58 | 16 | 152 |
| 150 | 150 | 160 | 162 | 42 | 152 |

These are averages over 20 random layouts each, produced by the project's test suite (`build/reports/search-benchmark.txt` after `./gradlew test`) against a simulated target, not a real server. Across a range of sizes and densities, the test suite also checks that a search never costs more than one-at-a-time removal plus 2·log₂(*n*) + 4 requests.

When nearly everything is required, BareMinimum sends a few more requests than one-at-a-time removal (48 instead of 42 for 40 items). With parallel requests it still finishes in about a third of the waiting time.

## Limitations

- In JSON and XML bodies, the first 1,000 items, up to 32 levels deep, are offered for removal. Anything beyond that is kept as it is.
- When a JSON container loses members, it is written out again in compact form (`"key": value` pairs separated by `, `). Containers that lose nothing stay exactly as they were.
- XML namespace declarations are never removed. The XML reader does not process DTDs or expand entities (by design), so a document that relies on entities declared in its DTD is minimized as plain text around them.
- Request targets with a `#fragment` are not treated specially.
- A response that varies a lot between identical requests needs Burp's response analysis, looser rules or a required string before a search can succeed.

## Building

Requires JDK 17 or newer. The Gradle wrapper is included.

```
./gradlew clean test jar
```

On Windows use `gradlew.bat`. The extension is written to `build/libs/BareMinimum-1.0.1.jar`. It has no dependencies beyond the Montoya API, which Burp provides.

## Installing

1. In Burp, open **Extensions > Installed** and click **Add**.
2. Leave **Extension type** as **Java** and select `build/libs/BareMinimum-1.0.1.jar`.

Requires Burp Suite 2023.12 or later.

## How it treats your data

- Request and response content is treated as untrusted. Names and values taken from HTTP messages are always displayed as plain text, never interpreted as HTML.
- JSON and XML bodies are read by small built-in parsers that only locate items and slice the original text. They never expand entities or fetch anything (no XXE), cannot be driven into deep recursion, and run in linear time.
- Nothing is written to disk except your own settings, stored in Burp's extension preferences. Saved profiles keep one minimized request each, in Burp's temporary storage, for the current session.
- All work that sends requests runs on a background thread, and stops when the extension is unloaded.

## License

Released under the [MIT License](LICENSE).
