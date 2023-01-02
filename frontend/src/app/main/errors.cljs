;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.errors
  "Generic error handling"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.pprint :as pp]
   [app.common.spec :as us]
   [app.config :as cf]
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
   [potok.core :as ptk]))

(defn on-error
  "A general purpose error handler."
  [error]
  (cond
    (instance? ExceptionInfo error)
    (let [data (ex-data error)]
      (if (contains? data :type)
        (ptk/handle-error data)
        (let [hint (str/ffmt "Unexpected error: '%'" (ex-message error))]
          (ts/schedule #(st/emit! (rt/assign-exception error)))
          (js/console.group hint)
          (js/console.log (.-stack error))
          (js/console.groupEnd hint))))

    (map? error)
    (ptk/handle-error error)

    :else
    (let [hint (ex-message error)
          msg  (dm/str "Internal Error: " hint)]
      (ts/schedule #(st/emit! (rt/assign-exception error)))

      (js/console.group msg)
      (ex/ignoring (js/console.error error))
      (js/console.groupEnd msg))))

;; Set the main potok error handler
(reset! st/on-error on-error)

(defmethod ptk/handle-error :default
  [error]
  (let [hint (str/ffmt "Unhandled error: '%'" (:hint error "[no hint]"))]
    (ts/schedule #(st/emit! (rt/assign-exception error)))
    (js/console.group hint)
    (ex/ignoring (js/console.error (pr-str error)))
    (js/console.groupEnd hint)))

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
  [error]
  (ts/schedule
   #(st/emit! (msg/show {:content "Validation error"
                         :type :error
                         :timeout 3000})))

  ;; Print to the console some debug info.
  (js/console.group "Validation Error:")
  (ex/ignoring
   (js/console.info
    (pp/pprint-str (dissoc error :explain))))

  (when-let [explain (:explain error)]
    (js/console.group "Spec explain:")
    (js/console.log explain)
    (js/console.groupEnd "Spec explain:"))

  (js/console.groupEnd "Validation Error:"))


;; All the errors that happens on worker are handled here.
(defmethod ptk/handle-error :worker-error
  [{:keys [code data hint] :as error}]
  (let [hint (or hint (:hint data) (:message data) (d/name code))
        info (pp/pprint-str (dissoc data :explain))
        msg  (dm/str "Internal Worker Error: " hint)]

    (ts/schedule
     #(st/emit!
       (msg/show {:content "Something wrong has happened (on worker)."
                  :type :error
                  :timeout 3000})))

    (js/console.group msg)
    (js/console.info info)

    (when-let [explain (:explain data)]
      (js/console.group "Spec explain:")
      (js/console.log explain)
      (js/console.groupEnd "Spec explain:"))

    (js/console.groupEnd msg)))

;; Error on parsing an SVG
;; TODO: looks unused and deprecated
(defmethod ptk/handle-error :svg-parser
  [_]
  (ts/schedule
   #(st/emit! (msg/show {:content "SVG is invalid or malformed"
                         :type :error
                         :timeout 3000}))))

;; TODO: should be handled in the event and not as general error handler
(defmethod ptk/handle-error :comment-error
  [_]
  (ts/schedule
   #(st/emit! (msg/show {:content "There was an error with the comment"
                         :type :error
                         :timeout 3000}))))

;; This is a pure frontend error that can be caused by an active
;; assertion (assertion that is preserved on production builds). From
;; the user perspective this should be treated as internal error.
(defmethod ptk/handle-error :assertion
  [{:keys [message hint] :as error}]
  (let [message (or message hint)
        message (dm/str "Internal Assertion Error: " message)
        context (dm/fmt "ns: '%'\nname: '%'\nfile: '%:%'"
                        (:ns error)
                        (:name error)
                        (dm/str @cf/public-uri "js/cljs-runtime/" (:file error))
                        (:line error))]

    (ts/schedule
     #(st/emit! (msg/show {:content "Internal error: assertion."
                           :type :error
                           :timeout 3000})))

    ;; Print to the console some debugging info
    (js/console.group message)
    (js/console.info context)
    (js/console.log (us/pretty-explain error))
    (js/console.groupEnd message)))

;; That are special case server-errors that should be treated
;; differently.

(derive :not-found ::exceptional-state)
(derive :bad-gateway ::exceptional-state)
(derive :service-unavailable ::exceptional-state)

(defmethod ptk/handle-error ::exceptional-state
  [error]
  (ts/schedule
   #(st/emit! (rt/assign-exception error))))

(defmethod ptk/handle-error :restriction
  [{:keys [code] :as error}]
  (cond
    (= :feature-mismatch code)
    (let [message (tr "errors.feature-mismatch" (:feature error))]
      (st/emit! (modal/show {:type :alert :message message})))

    (= :features-not-supported code)
    (let [message (tr "errors.feature-not-supported" (:feature error))]
      (st/emit! (modal/show {:type :alert :message message})))

    (= :max-quote-reached code)
    (let [message (tr "errors.max-quote-reached" (:target error))]
      (st/emit! (modal/show {:type :alert :message message})))

    :else
    (ptk/handle-error {:type :server-error :data error})))

;; This happens when the backed server fails to process the
;; request. This can be caused by an internal assertion or any other
;; uncontrolled error.

(defmethod ptk/handle-error :server-error
  [{:keys [data hint] :as error}]
  (let [hint (or hint (:hint data) (:message data))
        info (pp/pprint-str (dissoc data :explain))
        msg  (dm/str "Internal Server Error: " hint)]

    (ts/schedule
     #(st/emit!
       (msg/show {:content "Something wrong has happened (on backend)."
                  :type :error
                  :timeout 3000})))

    (js/console.group msg)
    (js/console.info info)

    (when-let [explain (:explain data)]
      (js/console.group "Spec explain:")
      (js/console.log explain)
      (js/console.groupEnd "Spec explain:"))

    (js/console.groupEnd msg)))

(defn on-unhandled-error
  [error]
  (letfn [(is-ignorable-exception? [cause]
            (let [message (ex-message cause)]
              (or (= message "Possible side-effect in debug-evaluate")
                  (= message "Unexpected end of input") true
                  (str/starts-with? message "Unexpected token "))))]
    (if (instance? ExceptionInfo error)
      (-> error ex-data ptk/handle-error)
      (when-not (is-ignorable-exception? error)
        (let [hint (ex-message error)
              msg  (dm/str "Unhandled Internal Error: " hint)]
          (ts/schedule #(st/emit! (rt/assign-exception error)))
          (js/console.group msg)
          (ex/ignoring (js/console.error error))
          (js/console.groupEnd msg))))))

(defonce uncaught-error-handler
  (letfn [(on-error [event]
            (.preventDefault ^js event)
            (some-> (unchecked-get event "error")
                    (on-unhandled-error)))]
    (.addEventListener glob/window "error" on-error)
    (fn []
      (.removeEventListener glob/window "error" on-error))))
