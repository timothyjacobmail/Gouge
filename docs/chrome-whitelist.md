# Chrome whitelist

An active Chrome whitelist entry permits that domain and its subdomains, or the saved path prefix. `gov.in` is supported explicitly, so approving it covers government agencies such as `traces.tdscpc.gov.in`. Lookalike domains such as `gov.in.example.com` do not match. Other broad public suffixes such as `co.in` remain invalid entries.

The first matching Allow domain rule also grants Chrome whitelist access. Existing approved rules take effect without another website password or wait. For example, Allow suffix `gov.in` covers TRACES, Allow suffix `org` covers `.org` websites, and an Exact Allow rule covers only the named host. An earlier Block rule wins; an earlier Allow rule wins over a later Block rule. Extra site locks, keywords, curfew, and app locks still apply.

Chrome cannot express hostname-contains rules in its managed URL patterns. For a matching Contains Allow rule, Gouge's accessibility service adds only the verified current hostname to the Chrome policy. A first blocked load may need a refresh after the approval toast. These hostnames are kept only in memory and are rechecked against current rule order on every policy refresh; removing or reordering a rule revokes the resulting access. No universal load-time wildcard is added.

Approved websites can load embedded Google reCAPTCHA, including the Enterprise widget used by Protean's PAN reprint page. Chrome receives HTTPS exceptions limited to `/recaptcha/` on the exact hosts `www.google.com`, `recaptcha.google.com`, `www.recaptcha.net`, and `www.gstatic.com`. These exceptions do not approve destinations in Gouge's address-bar checks. Extra site locks still override them. When there are no approved websites, no CAPTCHA exceptions are added.

Website additions and domain Allow rules keep their normal task passwords and approval waits. Websites without a matching Allow rule still need a separate website approval.

## Temporary links

For a shared Instagram reel or another one-off page, share it to Gouge and choose **Temporarily whitelist a link (1 day)**. You can also open **Chrome whitelist → Temporary links (1 day) → Add temporary link** and paste its full URL.

Confirm the request using the existing **Add Chrome whitelist sites** task. It uses that task's saved password delay, including any longer imported wait; there is no second wait. When approval completes, Gouge sends **Link available to view now**. Pending requests can be cancelled in **Pending tasks**. If notifications are disabled, enable them from Temporary links; the approved link remains available in that list.

The approval waits in a ready state until you choose **Open in Chrome — start 1 day**. Its 24-hour access window then starts, and reopening never extends the original deadline. Expiry removes only that temporary approval, including after process death or reboot. Ready approvals survive reboot; pending task waits restart according to the existing task rules. Removing a ready or active link is immediate. A fresh request after expiry requires the normal delay again. Permanent approvals and independent Allow rules still apply separately.

Only the approved page is permitted, including its content-identifying query parameters. Instagram post/reel paths identify content by their case-sensitive shortcode: `/p/ID`, `/reel/ID` and `/reels/ID` can display that same content, so these presentations share approval and their query parameters (including `xtok`) are omitted. Other sites retain identifying queries. UTM tags and common click IDs are omitted; fragments and a trailing slash do not change the approved page. Existing Instagram approvals are normalized on read without changing their ID, notification state or expiry. Native Instagram access is unchanged: links open explicitly in Chrome. Extra site locks, ordered Block rules, keywords, app locks, cooldowns and curfew still apply. Unlock Chrome through its existing controls if necessary before starting the link's timer.

Browser accessibility must be connected for temporary approvals to take effect. Chrome receives HTTPS/HTTP, port and exact-host filters limited to the approved path and query; its policy uses path prefixes, so Gouge's native address-bar monitor additionally checks the exact page and same-page navigation. See [Chromium's filter format](https://www.chromium.org/administrators/url-blocklist-filter-format/). Different redirect destinations, sign-in pages and other reels require their own approval. This is the existing Chrome/accessibility enforcement model, which can react after a page changes; it cannot guarantee no brief content flash.

Validation on 2026-11-11: 356 JVM tests passed. Version 1.9 (code 13) was installed over the existing app on the connected Vivo phone, preserving device-owner enrollment. All 23 focused Android tests passed: four temporary-link tests, fifteen Chrome policy tests and four native-password tests. These cover saved approval, authentication, cancellation, notifications, expiry and policy boundaries; live Instagram playback was not tested.

Version 1.9.1 fixes a live failure where an Instagram `xtok` share parameter became a required Chrome policy query. It also opens Instagram's canonical `www` address directly. Regression checks cover existing saved approvals, unchanged deadlines, clean URLs and alternate post/reel presentations, while rejecting other shortcodes, hosts and schemes. The phone's actual managed Chrome rules were checked, and its already-approved Instagram post was successfully displayed with native browser monitoring connected and the original expiry unchanged.

Live browser validation requires Chrome to be unlocked through the existing app controls. On the connected phone, Chrome was suspended and the browser accessibility service was off, so the live Protean widget could not be checked.

Validation on 2026-11-02: all 298 JVM tests and 14 Chrome policy tests on the connected Vivo phone passed. The device check confirmed that saved domain Allow rules reached Android's actual Chrome restrictions without changing saved website approvals. The app and instrumentation APKs built and the updated app was installed.
