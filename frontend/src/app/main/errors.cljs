;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.errors
  "Generic error handling"
  (:require
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.main.data.auth :as da]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.nitrate :as dnt]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace :as-alias dw]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.worker]
   [app.util.globals :as g]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; From app.main.data.workspace we can use directly because it causes a circular dependency
(def reload-file nil)

;; Will contain the latest error report assigned
(def last-report nil)

;; Will contain last uncaught exception
(def last-exception nil)

(defn is-plugin-error?
  "This is a placeholder that always return false. It will be
  overwritten when plugin system is initialized. This works this way
  because we can't import plugins here because plugins requires full
  DOM.

  This placeholder is set on app.plugins/initialize event"
  [_]
  false)

;; Re-entrancy guard: prevents on-error from calling itself recursively.
;; If an error occurs while we are already handling an error (e.g. the
;; notification emit itself throws), we log it and bail out immediately
;; instead of recursing until the call-stack overflows.
(def ^:private handling-error? (volatile! false))

;; --- Stale-asset error detection and auto-reload
;;
;; When the browser loads JS modules from different builds (e.g.  shared.js from
;; build A and main-dashboard.js from build B because you loaded it in the
;; middle of a deploy per example), keyword constants referenced across modules
;; will be undefined. This manifests as TypeError messages containing
;; "$cljs$cst$" and "is undefined" or "is null".

(defn stale-asset-error?
  "Returns true if the error matches the signature of a cross-build
  module mismatch. Two distinct patterns can appear depending on which
  cross-module reference is accessed first:

  1. Keyword constants  – names contain '$cljs$cst$'; these arise when a
     compiled keyword defined in shared.js is absent in the version of
     shared.js already resident in the browser.

  2. Protocol dispatch – names contain '$cljs$core$I'; these arise when
     main-workspace.js (new build) tries to invoke a protocol method on
     an object whose prototype was stamped by an older shared.js that
     used different mangled property names (e.g. the LazySeq /
     instaparse crash: 'Cannot read properties of undefined (reading
     \\'$cljs$core$IFn$_invoke$arity$1$\\')').

  Both patterns are symptoms of the same split-brain deployment
  scenario (browser has JS chunks from two different builds) and
  should trigger a hard page reload."
  [cause]
  (when (some? cause)
    (let [message (ex-message cause)]
      (and (string? message)
           (or (str/includes? message "$cljs$cst$")
               (str/includes? message "$cljs$core$I"))
           (or (str/includes? message "is undefined")
               (str/includes? message "is null")
               (str/includes? message "is not a function")
               (str/includes? message "Cannot read properties of undefined"))))))

(defn exception->error-data
  [cause]
  (let [data (ex-data cause)]
    (-> data
        (assoc :hint (or (:hint data) (ex-message cause)))
        (assoc ::instance cause)
        (assoc ::trace (.-stack cause)))))

(defn on-error
  "A general purpose error handler.

  Protected by a re-entrancy guard: if an error is raised while this
  function is already on the call stack (e.g. the notification emit
  itself fails), we print it to the console and return immediately
  instead of recursing until the call-stack is exhausted."
  [error]
  (if @handling-error?
    (do
      (js/console.error "[on-error] re-entrant call suppressed")
      (ex/print-throwable error))
    (do
      (vreset! handling-error? true)
      (try
        (if (map? error)
          (ptk/handle-error error)
          (let [data (exception->error-data error)]
            (ptk/handle-error data)))
        (finally
          (vreset! handling-error? false))))))

;; Inject dependency to remove circular dependency
(set! app.main.worker/on-error on-error)

;; Set the main potok error handler
(reset! st/on-error on-error)

(defn generate-report
  [cause]
  (try
    (let [team-id    (:current-team-id @st/state)
          file-id    (:current-file-id @st/state)
          profile-id (:profile-id @st/state)
          data       (ex-data cause)]

      (with-out-str
        (println "Context:")
        (println "--------------------")
        (println "Timestamp:" (ct/format-inst (ct/now) :rfc1123))
        (println "Hint:     " (or (:hint data) (ex-message cause) "--"))
        (println "Prof ID:  " (str (or profile-id "--")))
        (println "Team ID:  " (str (or team-id "--")))
        (when-let [file-id (or (:file-id data) file-id)]
          (println "File ID:  " (str file-id)))
        (println "Version:  " (:full cf/version))
        (println "HREF:     " (rt/get-current-href))
        (println)

        (println
         (ex/format-throwable cause))
        (println)

        (println "Last events:")
        (println "--------------------")
        (println (st/format-last-events))
        (println)))
    (catch :default err
      (.error js/console "error on generating report" err)
      ;; Keep this function total: `flash` reserves a report slot before
      ;; generating it, so returning nil here would consume the slot
      ;; without emitting anything.
      (str "Report generation failed: " (or (ex-message err) "--")
           "\nOriginal hint: " (or (ex/get-hint cause) "--")))))

;; --- Error report governor
;;
;; Bounds the volume of reports emitted by a single browser session. Each
;; report carries a fingerprint; the first occurrence is always emitted and
;; repeated occurrences of the same fingerprint within `report-window-ms`
;; are counted but not emitted. The next emitted report carries the number
;; of occurrences since the previous one as `:occurrences`. The report name
;; is part of the fingerprint, so a handled report never coalesces with an
;; unhandled/exception-page report of the same cause.
;;
;; The fingerprint cache is bounded: when it is full, the fingerprint
;; inserted first is evicted (FIFO order), so memory cannot grow without
;; limit.

(def report-window-ms
  "Minimum time between two reports with the same fingerprint."
  (* 2 60 1000))

(def max-tracked-fingerprints
  "Maximum number of fingerprints kept in the governor cache."
  2000)

(defn initial-report-state
  []
  {:entries {}
   :order   #queue []})

(defonce ^:private report-governor
  (atom (initial-report-state)))

(defn reset-report-governor!
  "Testing helper: clear the governor state."
  []
  (reset! report-governor (initial-report-state)))

(defn- label
  [v]
  (cond
    (nil? v)     ""
    (keyword? v) (name v)
    (string? v)  v
    :else        (str v)))

(defn error-fingerprint
  "Stable identity of an error, used to group repeated reports.

  The report name is part of the identity, so a `handled-exception` report
  never coalesces with an `unhandled-exception`/`exception-page` report of
  the same cause (those two do reach the error reports and alerts)."
  [event-name cause]
  (let [data  (ex-data cause)
        ftype (or (:type data) :unknown)
        code  (or (:code data) :unknown)
        hint  (or (ex/get-hint cause) "")
        ;; A JS stack string starts with "Error: <message>"; the first
        ;; actual frame is the second line.
        frame (or (some-> (.-stack cause) (str/lines) (second)) "")]
    (str (label event-name) "|" (label ftype) "|" (label code) "|"
         (str/prune hint 120) "|" (str/prune frame 120))))

(defn fallback-fingerprint
  "Fingerprint for reports submitted without a `cause` (e.g. the exception
  page or a stalled save)."
  [event-name hint]
  (str (label event-name) "|" (str/prune (or hint "") 120)))

(defn- evict-oldest
  "Drops the fingerprint inserted first. `:order` mirrors the insertion
  order of `:entries`, so this is O(1)."
  [state]
  (let [fingerprint (peek (:order state))]
    (-> state
        (update :entries dissoc fingerprint)
        (update :order pop))))

(defn reserve-report*
  "Pure decision step of the report governor.

  Given the governor `state`, an error `fingerprint` and the current time in
  milliseconds, returns the next governor state with this occurrence's
  decision attached: `::emit` tells whether it must be emitted and
  `::occurrences` carries the counter (present only when `::emit` is true)."
  [state fingerprint now]
  (let [entry   (get-in state [:entries fingerprint])
        emit?   (or (nil? entry)
                    (>= (- now (:emitted-at entry)) report-window-ms))
        pending (or (:pending entry) 0)]
    (cond
      ;; New fingerprint: insert it, evicting the oldest when the cache
      ;; is full.
      (and emit? (nil? entry))
      (let [state (cond-> state
                    (>= (count (:entries state)) max-tracked-fingerprints)
                    (evict-oldest))]
        (-> state
            (assoc-in [:entries fingerprint] {:emitted-at now :pending 0})
            (update :order conj fingerprint)
            (assoc ::emit true)
            (assoc ::occurrences (inc pending))))

      ;; Known fingerprint re-emitted after the window: keep its position.
      emit?
      (-> state
          (assoc-in [:entries fingerprint] {:emitted-at now :pending 0})
          (assoc ::emit true)
          (assoc ::occurrences (inc pending)))

      ;; Suppressed occurrence: only the counter moves.
      :else
      (-> state
          (update-in [:entries fingerprint :pending] inc)
          (assoc ::emit false)
          (dissoc ::occurrences)))))

(defn reserve-report!
  "Reserve a slot for a report. Returns the updated governor state, whose
  `::emit`/`::occurrences` describe the decision for this occurrence."
  [fingerprint now]
  (swap! report-governor reserve-report* fingerprint now))

(defn- emit-report!
  "Emit the audit event for a report that is already reserved by the
  governor."
  [event-name report hint occurrences]
  (st/emit!
   (ev/event {::ev/name event-name
              :hint hint
              :href (rt/get-current-href)
              :report report
              :occurrences occurrences})))

(defn submit-report
  "Report the error report to the audit log subsystem, subject to the
  report governor."
  [& {:keys [event-name report hint cause]
      :or {event-name "unhandled-exception"}}]
  (when (and (not (str/empty? hint))
             (string? report)
             (string? event-name))
    (let [state (reserve-report! (if (ex/exception? cause)
                                   (error-fingerprint event-name cause)
                                   (fallback-fingerprint event-name hint))
                                 (inst-ms (ct/now)))]
      (when (::emit state)
        (emit-report! event-name report hint (::occurrences state))))))

(defn flash
  "Show error notification banner and emit error report.
  A nil timeout keeps the notification visible until dismissed or replaced.

  The report is reserved before being generated, so repeated errors that
  fall inside the governor window do not pay the report-building cost.

  The notification is scheduled asynchronously (via tm/schedule) to
  avoid pushing a new event into the potok store while the store's own
  error-handling pipeline is still on the call stack.  Emitting
  synchronously from inside an error handler creates a re-entrant
  event-processing cycle that can exhaust the JS call stack
  (RangeError: Maximum call stack size exceeded)."
  [& {:keys [type hint cause timeout] :or {type :handled timeout 5000}}]
  (when (ex/exception? cause)
    (when-let [event-name (case type
                            :handled "handled-exception"
                            :unhandled "unhandled-exception"
                            :silent nil)]
      (let [report-hint (ex/get-hint cause)]
        (when (and (string? report-hint) (not (str/empty? report-hint)))
          (let [state (reserve-report! (error-fingerprint event-name cause) (inst-ms (ct/now)))]
            (when (::emit state)
              (emit-report! event-name
                            (generate-report cause)
                            report-hint
                            (::occurrences state))))))))

  (ts/schedule
   #(st/emit!
     (ntf/show {:content (or ^boolean hint (tr "errors.generic"))
                :type :toast
                :level :error
                :timeout timeout}))))

(defmethod ptk/handle-error :network
  [error]
  ;; Transient network errors (e.g. lost connectivity, DNS failure)
  ;; should not replace the entire page with an error screen. Show a
  ;; non-intrusive toast instead and let the user continue working.
  (when-let [cause (::instance error)]
    (ex/print-throwable cause :prefix "Network Error"))
  (flash :cause (::instance error) :type :handled))

(defn flash-persistence
  [cause]
  (let [{:keys [type cause-type]} (ex-data cause)]
    ;; Authentication has its own UI. `flash :silent` only skips reporting;
    ;; it still shows a toast, so do not call it for these failures.
    (when-not (or (= :authentication type) (= :authentication cause-type))
      (flash :cause cause :type :handled :timeout nil :hint (tr "errors.save-failed")))))

(defmethod ptk/handle-error :persistence
  [error]
  ;; The persistence failure event reports the original cause. Waiters still
  ;; reject, but must not report that same incident again.
  (when-not (::handled? error)
    (flash-persistence (::instance error))))

(defmethod ptk/handle-error :internal
  [error]
  (st/emit! (rt/assign-exception error))
  (when-let [cause (::instance error)]
    (ex/print-throwable cause :prefix "Internal Error")))

(defmethod ptk/handle-error :default
  [error]
  (if (and (string? (:hint error))
           (str/starts-with? (:hint error) "Assert failed:"))
    (ptk/handle-error (assoc error :type :assertion))
    (when-let [cause (::instance error)]
      (ex/print-throwable cause :prefix "Unexpected Error")
      (flash :cause cause :type :unhandled))))

(defmethod ptk/handle-error :wasm-error
  [error]
  (when-let [cause (::instance error)]
    (ex/print-throwable cause)
    (let [code (get error :code)]
      (cond
        (= code :panic)
        (st/emit! (rt/assign-exception error))

        :else
        (flash :type :handled :cause cause)))))

;; We receive a explicit authentication error; If the uri is for
;; workspace, dashboard, viewer or settings, then assign the exception
;; for show the error page. Otherwise this explicitly clears all
;; profile data and redirect the user to the login page.
(defn- show-authentication-error
  [error]
  (let [message (tr "errors.auth.unable-to-login")
        uri     (rt/get-current-href)

        show-error?
        (or (str/includes? uri "workspace")
            (str/includes? uri "dashboard")
            (str/includes? uri "view")
            (str/includes? uri "settings"))]

    (if show-error?
      (st/async-emit! (rt/assign-exception error))
      (do
        (st/emit! (da/logout))
        (ts/schedule 500 #(st/emit! (ntf/warn message)))))))

;; The user does belong to an organization with SSO active, but there is
;; no provider to send them to (unusable or incomplete SSO config). Show
;; the SSO error dialog, which offers an explicit retry, rather than
;; claiming they have no access.
(defn- show-sso-error
  [{:keys [organization-id team-id]}]
  (let [uri (rt/get-current-href)]
    (st/async-emit!
     (rt/assign-exception {:type :sso-error
                           :organization-id organization-id
                           :team-id team-id
                           :is-workspace (str/includes? uri "workspace")
                           :is-dashboard (str/includes? uri "dashboard")}))))

;; A page issues many SSO-guarded requests at once, and all of them fail
;; together the moment the organization SSO session lapses; without this
;; only-one-in-flight guard each of them would start its own identity
;; provider round-trip.
(def ^:private sso-renewal-pending? (volatile! false))

(defn- renew-organization-sso
  "Recover from a request rejected by the organization SSO gate.

  Asks the backend what can be done for the current location and acts on
  the answer: go through the identity provider when there is one (it
  re-authenticates transparently while the user still has a live session
  with it), retry the location when the gate turns out to be satisfied
  already (another tab renewed the session, or SSO was turned off), show
  the SSO error dialog when SSO is required but unusable, and report a
  permission failure only when the user really has no access to the team.
  A failing check is left to the generic error handling, so a network
  blip is not turned into a permission error."
  [{:keys [organization-id team-id] :as error}]
  (when-not @sso-renewal-pending?
    (vreset! sso-renewal-pending? true)
    (let [dest-url (rt/get-current-href)]
      (->> (dnt/check-organization-sso
            {:organization-id organization-id
             :team-id team-id
             :dest-url dest-url})
           ;; Release the guard however the check ends, including an
           ;; unsubscription or a completion without a result: a stuck guard
           ;; would silently drop every later rejection.
           (rx/finalize (fn [] (vreset! sso-renewal-pending? false)))
           (rx/subs! (fn [{:keys [authorized reason redirect-uri]}]
                       (cond
                         ;; SSO must be renewed and we know where to send them
                         (some? redirect-uri)
                         (st/emit! (rt/nav-raw :uri (str redirect-uri)))

                         ;; The gate is satisfied after all, so the request
                         ;; that failed can be retried. Only an affirmative
                         ;; reason is accepted here: reloading on any
                         ;; unrecognized "authorized" answer would spin
                         ;; whenever the reload hits the same rejection.
                         (= :sso-satisfied reason)
                         (st/emit! (rt/reload false))

                         ;; SSO is required but the provider is unusable
                         (not authorized)
                         (show-sso-error error)

                         ;; No access to the team, so the gate was never
                         ;; evaluated: this really is a permission failure
                         :else
                         (show-authentication-error error)))
                     on-error)))))

(defmethod ptk/handle-error :authentication
  [error]
  ;; Without an organization or a team there is nothing to check, and asking
  ;; anyway would fail schema validation and report that instead of the
  ;; authentication problem the user actually hit.
  (if (and (= :nitrate-sso-required (get error :code))
           (or (some? (get error :organization-id))
               (some? (get error :team-id))))
    (renew-organization-sso error)
    (show-authentication-error error)))

;; Error that happens on an active business model validation does not
;; passes an validation (example: profile can't leave a team). From
;; the user perspective a error flash message should be visualized but
;; user can continue operate on the application. Can happen in backend
;; and frontend.

(defmethod ptk/handle-error :validation
  [{:keys [code] :as error}]

  (when-let [instance (get error ::instance)]
    (ex/print-throwable instance :prefix "Validation Error"))

  (cond
    (= code :invalid-paste-data)
    (let [message (tr "errors.paste-data-validation")]
      (st/async-emit!
       (ntf/show {:content message
                  :type :toast
                  :level :error
                  :timeout 3000})))

    (= code :vern-conflict)
    (st/emit! (ptk/event ::dw/reload-current-file))

    (= code :snapshot-is-locked)
    (let [message (tr "errors.version-locked")]
      (st/async-emit!
       (ntf/show {:content message
                  :type :toast
                  :level :error
                  :timeout 3000})))

    (= code :only-creator-can-lock)
    (let [message (tr "errors.only-creator-can-lock")]
      (st/async-emit!
       (ntf/show {:content message
                  :type :toast
                  :level :error
                  :timeout 3000})))

    (= code :only-creator-can-unlock)
    (let [message (tr "errors.only-creator-can-unlock")]
      (st/async-emit!
       (ntf/show {:content message
                  :type :toast
                  :level :error
                  :timeout 3000})))

    (= code :snapshot-already-locked)
    (let [message (tr "errors.version-already-locked")]
      (st/async-emit!
       (ntf/show {:content message
                  :type :toast
                  :level :error
                  :timeout 3000})))

    (= code :invalid-sso-config)
    ;; SSO error page needs :organization-id to retry
    (if (:organization-id error)
      (st/async-emit! (rt/assign-exception (assoc error :type :sso-error)))
      (st/async-emit! (rt/assign-exception error)))

    :else
    (st/async-emit! (rt/assign-exception error))))

;; This is a pure frontend error that can be caused by an active
;; assertion (assertion that is preserved on production builds).
(defmethod ptk/handle-error :assertion
  [error]
  (when-let [cause (::instance error)]
    (flash :cause cause :type :handled)
    (ex/print-throwable cause :prefix "Assertion Error")))

;; ;; All the errors that happens on worker are handled here.
(defmethod ptk/handle-error :worker-error
  [error]
  (ts/schedule
   #(st/emit!
     (ntf/show {:content (tr "errors.internal-worker-error")
                :type :toast
                :level :error
                :timeout 3000})))

  (some-> (::instance error)
          (ex/print-throwable :prefix "Web Worker Error")))

;; Error on parsing an SVG
(defmethod ptk/handle-error :svg-parser
  [_]
  (ts/schedule
   #(st/emit! (ntf/show {:content (tr "errors.svg-parser.invalid-svg")
                         :type :toast
                         :level :error
                         :timeout 3000}))))

;; TODO: should be handled in the event and not as general error handler
(defmethod ptk/handle-error :comment-error
  [_]
  (ts/schedule
   #(st/emit! (ntf/show {:content (tr "errors.comment-error")
                         :type :toast
                         :level :error
                         :timeout 3000}))))

;; That are special case server-errors that should be treated
;; differently.

(defn- handle-exceptional-state
  [error]
  (when-let [instance (get error ::instance)]
    (ex/print-throwable instance :prefix "Exceptional State"))
  (ts/schedule #(st/emit! (rt/assign-exception error))))

(defmethod ptk/handle-error :not-found [error] (handle-exceptional-state error))
(defmethod ptk/handle-error :bad-gateway [error] (handle-exceptional-state error))
(defmethod ptk/handle-error :service-unavailable [error] (handle-exceptional-state error))
(defmethod ptk/handle-error :nitrate-unavailable [error] (handle-exceptional-state error))
(defmethod ptk/handle-error :nitrate-not-configured [error] (handle-exceptional-state error))

(defn- redirect-to-dashboard
  []
  (let [team-id    (:current-team-id @st/state)
        project-id (:current-project-id @st/state)]
    (if (and project-id team-id)
      (st/emit! (rt/nav :dashboard-files {:team-id team-id :project-id project-id}))
      (set! (.-href g/location) ""))))

(defmethod ptk/handle-error :restriction
  [{:keys [code] :as error}]
  (cond
    (= :migration-in-progress code)
    (let [message    (tr "errors.migration-in-progress" (:feature error))
          on-accept  (constantly nil)]
      (st/emit! (modal/show {:type :alert :message message :on-accept on-accept})))

    (= :team-feature-mismatch code)
    (let [message    (tr "errors.team-feature-mismatch" (:feature error))
          on-accept  (constantly nil)]
      (st/emit! (modal/show {:type :alert :message message :on-accept on-accept})))

    (= :file-feature-mismatch code)
    (let [message (tr "errors.file-feature-mismatch" (:feature error))]
      (st/emit! (modal/show {:type :alert :message message :on-accept redirect-to-dashboard})))

    (= :feature-mismatch code)
    (let [message (tr "errors.feature-mismatch" (:feature error))]
      (st/emit! (modal/show {:type :alert :message message :on-accept redirect-to-dashboard})))

    (= :feature-not-supported code)
    (let [message (tr "errors.feature-not-supported" (:feature error))]
      (st/emit! (modal/show {:type :alert :message message :on-accept redirect-to-dashboard})))

    (= :file-version-not-supported code)
    (let [message (tr "errors.version-not-supported")]
      (st/emit! (modal/show {:type :alert :message message :on-accept redirect-to-dashboard})))

    (= :max-quote-reached code)
    (let [message (tr "errors.max-quota-reached" (:target error))]
      (st/emit! (modal/show {:type :alert :message message})))

    (or (= :paste-feature-not-enabled code)
        (= :missing-features-in-paste-content code)
        (= :paste-feature-not-supported code))
    (let [message (tr "errors.feature-not-supported" (:feature error))]
      (st/emit! (modal/show {:type :alert :message message})))

    (= :file-in-components-v1 code)
    (st/emit! (modal/show {:type :alert
                           :message (tr "errors.deprecated")
                           :link-message {:before (tr "errors.deprecated.contact.before")
                                          :text (tr "errors.deprecated.contact.text")
                                          :after (tr "errors.deprecated.contact.after")
                                          :on-click #(st/emit! (rt/nav :settings-feedback))}}))
    :else
    (when-let [cause (::instance error)]
      (ex/print-throwable cause :prefix "Restriction Error")
      (flash :cause cause :type :unhandled))))

;; This happens when the backed server fails to process the
;; request. This can be caused by an internal assertion or any other
;; uncontrolled error.

(defmethod ptk/handle-error :server-error
  [error]
  (when-let [instance (get error ::instance)]
    (ex/print-throwable instance :prefix "Server Error"))
  (st/async-emit! (rt/assign-exception error)))

(defn- from-extension?
  "True when the error stack trace originates from a browser extension."
  [cause]
  (let [stack (.-stack cause)]
    (and (string? stack)
         (or (str/includes? stack "chrome-extension://")
             (str/includes? stack "moz-extension://")
             ;; Safari/WebKit masks extension and Web Inspector URLs
             ;; with this internal scheme.
             (str/includes? stack "webkit-masked-url://")))))

(defn- from-posthog?
  "True when the error stack trace originates from PostHog analytics."
  [cause]
  (let [stack (.-stack cause)]
    (and (string? stack)
         (str/includes? stack "posthog"))))

(defn is-ignorable-exception?
  "True when the error is known to be harmless (browser extensions, analytics,
   React/extension DOM conflicts, etc.) and should NOT be surfaced to the user."
  [cause]
  (let [message (ex-message cause)]
    (or (from-extension? cause)
        (from-posthog? cause)
        (= message "Possible side-effect in debug-evaluate")
        (= message "Unexpected end of input")
        (str/starts-with? message "invalid props on component")
        (str/starts-with? message "Unexpected token ")
        ;; Native AbortError DOMException: raised when an in-flight
        ;; HTTP fetch is cancelled via AbortController (e.g. by an
        ;; RxJS unsubscription / take-until chain).  These are
        ;; handled gracefully inside app.util.http/fetch and must NOT
        ;; be surfaced as application errors.
        (= (.-name ^js cause) "AbortError")
        ;; Zone.js (injected by browser extensions such as Angular
        ;; DevTools) wraps event listeners and assigns a custom
        ;; .toString to its wrapper functions using
        ;; Object.defineProperty.  When the wrapper was previously
        ;; defined with {writable: false}, a subsequent plain assignment
        ;; in strict mode (our libs.js uses "use strict") throws this
        ;; TypeError.  This is a known Zone.js / browser-extension
        ;; incompatibility and is NOT a Penpot bug.
        (str/starts-with? message "Cannot assign to read only property 'toString'")
        ;; Safari TypeError: "Attempting to change value of a readonly
        ;; property".  Raised when browser extensions or Web Inspector
        ;; devtools (e.g., jsonPrune) try to mutate ClojureScript's
        ;; immutable data structures via Object.defineProperty.
        ;; ClojureScript defines getter-only properties on its maps
        ;; and records, making them readonly.  This is NOT a Penpot bug.
        (and (= (.-name ^js cause) "TypeError")
             (= message "Attempting to change value of a readonly property"))
        ;; NotFoundError DOMException: "Failed to execute
        ;; 'removeChild' on 'Node'" — Thrown by React's commit
        ;; phase when the DOM tree has been modified externally
        ;; (typically by browser extensions like Grammarly,
        ;; LastPass, translation tools, or ad blockers that
        ;; inject/remove nodes).  The entire stack trace is inside
        ;; React internals (libs.js) with no application code,
        ;; so there is nothing actionable on our side.  React's
        ;; error boundary already handles recovery.
        (and (= (.-name ^js cause) "NotFoundError")
             (str/includes? message "removeChild")))))

(defn- from-plugin?
  "Check if the error is marked as originating from plugin code. The
  plugin runtime tracks plugin errors in a WeakMap, which works even
  in SES hardened environments where error objects may be frozen."
  [cause]
  (try
    (is-plugin-error? cause)
    (catch :default _
      false)))

(defonce uncaught-error-handler
  (letfn [(on-unhandled-error [event]
            (.preventDefault ^js event)
            (when-let [cause (unchecked-get event "error")]
              (cond
                (stale-asset-error? cause)
                (cf/throttled-reload :reason (ex-message cause))

                ;; Plugin errors: log to console and ignore
                (from-plugin? cause)
                (ex/print-throwable cause :prefix "Plugin Error")

                ;; Other ignorable exceptions: ignore silently
                (is-ignorable-exception? cause)
                nil

                ;; All other errors: show exception page
                :else

                (let [data (ex-data cause)
                      type (get data :type)]
                  (set! last-exception cause)
                  (if (= :wasm-error type)
                    (on-error cause)
                    (do
                      (ex/print-throwable cause :prefix "Uncaught Exception")
                      (ts/asap #(flash :cause cause :type :unhandled))))))))

          (on-unhandled-rejection [event]
            (.preventDefault ^js event)
            (when-let [cause (unchecked-get event "reason")]
              (cond
                (stale-asset-error? cause)
                (cf/throttled-reload :reason (ex-message cause))

                ;; Plugin errors: log to console and ignore
                (from-plugin? cause)
                (ex/print-throwable cause :prefix "Plugin Error")

                ;; Other ignorable exceptions: ignore silently
                (is-ignorable-exception? cause)
                nil

                ;; All other errors: show exception page
                :else
                (let [data (ex-data cause)
                      type (get data :type)]
                  (set! last-exception cause)
                  (if (= :wasm-error type)
                    (on-error cause)
                    (do
                      (ex/print-throwable cause :prefix "Uncaught Rejection")
                      (ts/asap #(flash :cause cause :type :unhandled))))))))]

    (.addEventListener g/window "error" on-unhandled-error)
    (.addEventListener g/window "unhandledrejection" on-unhandled-rejection)
    (fn []
      (.removeEventListener g/window "error" on-unhandled-error)
      (.removeEventListener g/window "unhandledrejection" on-unhandled-rejection))))
