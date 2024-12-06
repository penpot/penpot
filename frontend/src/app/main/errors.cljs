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
   [app.common.schema :as sm]
   [app.main.data.auth :as da]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace :as-alias dw]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.globals :as glob]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; From app.main.data.workspace we can use directly because it causes a circular dependency
(def reload-file nil)

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
  (when-let [{:keys [errors] :as explain} (::sm/explain data)]
    (let [errors (mapv #(update % :schema sm/form) errors)]
      (pp/pprint errors {:width 100 :level 15 :length 20})))

  (when-let [explain (:explain data)]
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

(defn exception->error-data
  [cause]
  (let [data (ex-data cause)]
    (-> data
        (assoc :hint (or (:hint data) (ex-message cause)))
        (assoc ::instance cause)
        (assoc ::trace (.-stack cause)))))

(defn print-error!
  [cause]
  (cond
    (map? cause)
    (print-cause! (:hint cause "Unexpected Error") cause)

    (ex/error? cause)
    (print-cause! (ex-message cause) (ex-data cause))

    :else
    (print-cause! (ex-message cause) (exception->error-data cause))))

(defn on-error
  "A general purpose error handler."
  [error]
  (if (map? error)
    (ptk/handle-error error)
    (let [data (exception->error-data error)]
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

;; We receive a explicit authentication error;
;; If the uri is for workspace, dashboard or view assign the
;; exception for the 'Oops' page. Otherwise this explicitly clears
;; all profile data and redirect the user to the login page. This is
;; here and not in app.main.errors because of circular dependency.
(defmethod ptk/handle-error :authentication
  [e]
  (let [msg        (tr "errors.auth.unable-to-login")
        uri        (.-href glob/location)
        show-oops? (or (str/includes? uri "workspace")
                       (str/includes? uri "dashboard")
                       (str/includes? uri "view"))]
    (if show-oops?
      (st/async-emit! (rt/assign-exception e))
      (do
        (st/emit! (da/logout))
        (ts/schedule 500 #(st/emit! (ntf/warn msg)))))))

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
       (ntf/show {:content message
                  :type :toast
                  :level :error
                  :timeout 3000})))

    (= code :vern-conflict)
    (st/emit! (ptk/event ::dw/reload-current-file))

    :else
    (st/async-emit! (rt/assign-exception error))))


;; This is a pure frontend error that can be caused by an active
;; assertion (assertion that is preserved on production builds). From
;; the user perspective this should be treated as internal error.
(defmethod ptk/handle-error :assertion
  [error]
  (ts/schedule
   #(st/emit! (ntf/show {:content "Internal Assertion Error"
                         :type :toast
                         :level :error
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
     (ntf/show {:content "Something wrong has happened (on worker)."
                :type :toast
                :level :error
                :timeout 3000})))

  (print-group! "Internal Worker Error"
                (fn []
                  (print-data! error))))

;; Error on parsing an SVG
;; TODO: looks unused and deprecated
(defmethod ptk/handle-error :svg-parser
  [_]
  (ts/schedule
   #(st/emit! (ntf/show {:content "SVG is invalid or malformed"
                         :type :toast
                         :level :error
                         :timeout 3000}))))

;; TODO: should be handled in the event and not as general error handler
(defmethod ptk/handle-error :comment-error
  [_]
  (ts/schedule
   #(st/emit! (ntf/show {:content "There was an error with the comment"
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
                  (str/starts-with? message "invalid props on component")
                  (str/starts-with? message "Unexpected token "))))

          (on-unhandled-error [event]
            (.preventDefault ^js event)
            (when-let [error (unchecked-get event "error")]
              (when-not (is-ignorable-exception? error)
                (on-error error))))]

    (.addEventListener glob/window "error" on-unhandled-error)
    (fn []
      (.removeEventListener glob/window "error" on-unhandled-error))))
