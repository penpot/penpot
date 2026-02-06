;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.errors
  "Generic error handling"
  (:require
   [app.common.exceptions :as ex]
   [app.common.pprint :as pp]
   [app.config :as cf]
   [app.main.data.auth :as da]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace :as-alias dw]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.worker]
   [app.util.globals :as g]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; From app.main.data.workspace we can use directly because it causes a circular dependency
(def reload-file nil)

;; Will contain the latest error report assigned
(def last-report nil)

;; Will contain last uncaught exception
(def last-exception nil)

(defn exception->error-data
  [cause]
  (let [data (ex-data cause)]
    (-> data
        (assoc :hint (or (:hint data) (ex-message cause)))
        (assoc ::instance cause)
        (assoc ::trace (.-stack cause)))))

(defn on-error
  "A general purpose error handler."
  [error]
  (if (map? error)
    (ptk/handle-error error)
    (let [data (exception->error-data error)]
      (ptk/handle-error data))))

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
        (println "Hint:    " (or (:hint data) (ex-message cause) "--"))
        (println "Prof ID: " (str (or profile-id "--")))
        (println "Team ID: " (str (or team-id "--")))
        (when-let [file-id (or (:file-id data) file-id)]
          (println "File ID: " (str file-id)))
        (println "Version: " (:full cf/version))
        (println "URI:     " (str cf/public-uri))
        (println "HREF:    " (rt/get-current-href))
        (println)

        (println
         (ex/format-throwable cause))
        (println)

        (println "Last events:")
        (println "--------------------")
        (pp/pprint @st/last-events {:length 200})
        (println)))
    (catch :default cause
      (.error js/console "error on generating report" cause)
      nil)))

(defn- show-not-blocking-error
  "Show a non user blocking error notification"
  [cause]
  (let [data (ex-data cause)
        hint (or (some-> (:hint data) ex/first-line)
                 (ex-message cause))]

    (st/emit!
     (ev/event {::ev/name "unhandled-exception"
                :hint hint
                :href (rt/get-current-href)
                :type (get data :type :unknown)
                :report (generate-report cause)})

     (ntf/show {:content (tr "errors.unexpected-exception" hint)
                :type :toast
                :level :error
                :timeout 3000}))))

(defmethod ptk/handle-error :default
  [error]
  (if (and (string? (:hint error))
           (str/starts-with? (:hint error) "Assert failed:"))
    (ptk/handle-error (assoc error :type :assertion))
    (when-let [cause (::instance error)]
      (ex/print-throwable cause :prefix "Unexpected Error")
      (show-not-blocking-error cause))))

;; We receive a explicit authentication error; If the uri is for
;; workspace, dashboard, viewer or settings, then assign the exception
;; for show the error page. Otherwise this explicitly clears all
;; profile data and redirect the user to the login page. This is here
;; and not in app.main.errors because of circular dependency.
(defmethod ptk/handle-error :authentication
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

    :else
    (st/async-emit! (rt/assign-exception error))))

;; This is a pure frontend error that can be caused by an active
;; assertion (assertion that is preserved on production builds). From
;; the user perspective this should be treated as internal error.
(defmethod ptk/handle-error :assertion
  [error]
  (when-let [cause (::instance error)]
    (show-not-blocking-error cause)
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

(derive :not-found ::exceptional-state)
(derive :bad-gateway ::exceptional-state)
(derive :service-unavailable ::exceptional-state)

(defmethod ptk/handle-error ::exceptional-state
  [error]
  (when-let [instance (get error ::instance)]
    (ex/print-throwable instance :prefix "Exceptional State"))
  (ts/schedule #(st/emit! (rt/assign-exception error))))

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
      (show-not-blocking-error cause))))

;; This happens when the backed server fails to process the
;; request. This can be caused by an internal assertion or any other
;; uncontrolled error.

(defmethod ptk/handle-error :server-error
  [error]
  (when-let [instance (get error ::instance)]
    (ex/print-throwable instance :prefix "Server Error"))
  (st/async-emit! (rt/assign-exception error)))

(defonce uncaught-error-handler
  (letfn [(is-ignorable-exception? [cause]
            (let [message (ex-message cause)]
              (or (= message "Possible side-effect in debug-evaluate")
                  (= message "Unexpected end of input")
                  (str/starts-with? message "invalid props on component")
                  (str/starts-with? message "Unexpected token "))))

          (on-unhandled-error [event]
            (.preventDefault ^js event)
            (when-let [cause (unchecked-get event "error")]
              (set! last-exception cause)
              (when-not (is-ignorable-exception? cause)
                (ex/print-throwable cause :prefix "Uncaught Exception")
                (ts/schedule #(show-not-blocking-error cause)))))

          (on-unhandled-rejection [event]
            (.preventDefault ^js event)
            (when-let [cause (unchecked-get event "reason")]
              (set! last-exception cause)
              (ex/print-throwable cause :prefix "Uncaught Rejection")
              (ts/schedule #(show-not-blocking-error cause))))]

    (.addEventListener g/window "error" on-unhandled-error)
    (.addEventListener g/window "unhandledrejection" on-unhandled-rejection)
    (fn []
      (.removeEventListener g/window "error" on-unhandled-error)
      (.removeEventListener g/window "unhandledrejection" on-unhandled-rejection))))

