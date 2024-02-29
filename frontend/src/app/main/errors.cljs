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
   [app.common.schema :as-alias sm]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.util.globals :as glob]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.storage :refer [storage]]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(defn- print-data!
  [data]
  (-> data
      (dissoc ::sm/explain)
      (dissoc :explain)
      (dissoc ::trace)
      (dissoc ::instance)
      (pp/pprint {:width 70})))

(defn- print-explain!
  [data]
  (when-let [explain (or (ex/explain data)
                         (:explain data))]
    (js/console.log explain)))

(defn- print-trace!
  [data]
  (some-> data ::trace js/console.log))

(defn- print-group!
  [message f]
  (try
    (js/console.group message)
    (f)
    (catch :default _ nil)
    (finally
      (js/console.groupEnd message))))

(defn print-cause!
  [message cause]
  (print-group! message (fn []
                          (print-data! cause)
                          (print-explain! cause)
                          (print-trace! cause))))

(defn print-error!
  [cause]
  (cond
    (map? cause)
    (print-cause! (:hint cause "Unexpected Error") cause)

    (ex/error? cause)
    (print-cause! (ex-message cause) (ex-data cause))

    :else
    (let [trace (.-stack cause)]
      (print-cause! (ex-message cause)
                    {:hint (ex-message cause)
                     ::trace trace
                     ::instance cause}))))

(defn on-error
  "A general purpose error handler."
  [error]
  (if (map? error)
    (ptk/handle-error error)
    (let [data (ex-data error)
          data (-> data
                   (assoc :hint (or (:hint data) (ex-message error)))
                   (assoc ::instance error)
                   (assoc ::trace (.-stack error)))]
      (ptk/handle-error data))))

;; Set the main potok error handler
(reset! st/on-error on-error)

(defmethod ptk/handle-error :default
  [error]
  (st/async-emit! (rt/assign-exception error))
  (print-group! "Unhandled Error"
                (fn []
                  (print-trace! error)
                  (print-data! error))))

;; We receive a explicit authentication error; this explicitly clears
;; all profile data and redirect the user to the login page. This is
;; here and not in app.main.errors because of circular dependency.
(defmethod ptk/handle-error :authentication
  [_]
  (let [msg (tr "errors.auth.unable-to-login")
        uri (. (. js/document -location) -href)]
    (st/emit! (du/logout {:capture-redirect true}))
    (ts/schedule 500 #(st/emit! (msg/warn msg)))
    (ts/schedule 1000 #(swap! storage assoc :redirect-url uri))))

;; Error that happens on an active business model validation does not
;; passes an validation (example: profile can't leave a team). From
;; the user perspective a error flash message should be visualized but
;; user can continue operate on the application. Can happen in backend
;; and frontend.

(defmethod ptk/handle-error :validation
  [{:keys [code] :as error}]
  (print-group! "Validation Error"
                (fn []
                  (print-data! error)
                  (print-explain! error)))
  (cond
    (= code :invalid-paste-data)
    (let [message (tr "errors.paste-data-validation")]
      (st/async-emit!
       (msg/show {:content message
                  :notification-type :toast
                  :type :error
                  :timeout 3000})))

    :else
    (st/async-emit! (rt/assign-exception error))))


;; This is a pure frontend error that can be caused by an active
;; assertion (assertion that is preserved on production builds). From
;; the user perspective this should be treated as internal error.
(defmethod ptk/handle-error :assertion
  [error]
  (ts/schedule
   #(st/emit! (msg/show {:content "Internal Assertion Error"
                         :notification-type :toast
                         :type :error
                         :timeout 3000})))

  (print-group! "Internal Assertion Error"
                (fn []
                  (print-trace! error)
                  (print-data! error)
                  (print-explain! error))))

;; ;; All the errors that happens on worker are handled here.
(defmethod ptk/handle-error :worker-error
  [error]
  (ts/schedule
   #(st/emit!
     (msg/show {:content "Something wrong has happened (on worker)."
                :notification-type :toast
                :type :error
                :timeout 3000})))

  (print-group! "Internal Worker Error"
                (fn []
                  (print-data! error))))

;; Error on parsing an SVG
;; TODO: looks unused and deprecated
(defmethod ptk/handle-error :svg-parser
  [_]
  (ts/schedule
   #(st/emit! (msg/show {:content "SVG is invalid or malformed"
                         :notification-type :toast
                         :type :error
                         :timeout 3000}))))

;; TODO: should be handled in the event and not as general error handler
(defmethod ptk/handle-error :comment-error
  [_]
  (ts/schedule
   #(st/emit! (msg/show {:content "There was an error with the comment"
                         :notification-type :toast
                         :type :error
                         :timeout 3000}))))

;; That are special case server-errors that should be treated
;; differently.

(derive :not-found ::exceptional-state)
(derive :bad-gateway ::exceptional-state)
(derive :service-unavailable ::exceptional-state)

(defmethod ptk/handle-error ::exceptional-state
  [error]
  (when-let [cause (::instance error)]
    (js/console.log (.-stack cause)))

  (ts/schedule
   #(st/emit! (rt/assign-exception error))))


(defn- redirect-to-dashboard
  []
  (let [team-id    (:current-team-id @st/state)
        project-id (:current-project-id @st/state)]
    (if (and project-id team-id)
      (st/emit! (rt/nav :dashboard-files {:team-id team-id :project-id project-id}))
      (set! (.-href glob/location) ""))))

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
    (let [message (tr "errors.max-quote-reached" (:target error))]
      (st/emit! (modal/show {:type :alert :message message})))

    (or (= :paste-feature-not-enabled code)
        (= :missing-features-in-paste-content code)
        (= :paste-feature-not-supported code))
    (let [message (tr "errors.feature-not-supported" (:feature error))]
      (st/emit! (modal/show {:type :alert :message message})))

    :else
    (print-cause! "Restriction Error" error)))

;; This happens when the backed server fails to process the
;; request. This can be caused by an internal assertion or any other
;; uncontrolled error.

(defmethod ptk/handle-error :server-error
  [error]
  (st/async-emit! (rt/assign-exception error))
  (print-group! "Server Error"
                (fn []
                  (print-data! (dissoc error :data))

                  (when-let [werror (:data error)]
                    (cond
                      (= :assertion (:type werror))
                      (print-group! "Assertion Error"
                                    (fn []
                                      (print-data! werror)
                                      (print-explain! werror)))

                      :else
                      (print-group! "Unexpected"
                                    (fn []
                                      (print-data! werror)
                                      (print-explain! werror))))))))


(defonce uncaught-error-handler
  (letfn [(is-ignorable-exception? [cause]
            (let [message (ex-message cause)]
              (or (= message "Possible side-effect in debug-evaluate")
                  (= message "Unexpected end of input")
                  (str/starts-with? message "Unexpected token "))))

          (on-unhandled-error [event]
            (.preventDefault ^js event)
            (when-let [error (unchecked-get event "error")]
              (when-not (is-ignorable-exception? error)
                (on-error error))))]

    (.addEventListener glob/window "error" on-unhandled-error)
    (fn []
      (.removeEventListener glob/window "error" on-unhandled-error))))
