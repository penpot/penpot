;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.errors
  "Generic error handling"
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [app.main.data.messages :as msg]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.util.globals :as glob]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.timers :as ts]
   [expound.alpha :as expound]
   [fipp.edn :as fpp]
   [potok.core :as ptk]))

(defn on-error
  "A general purpose error handler."
  [error]
  (cond
    (instance? ExceptionInfo error)
    (-> error ex-data ptk/handle-error)

    (map? error)
    (ptk/handle-error error)

    :else
    (let [hint (ex-message error)
          msg  (dm/str "Internal Error: " hint)]
      (ts/schedule (st/emitf (rt/assign-exception error)))

      (js/console.group msg)
      (ex/ignoring (js/console.error error))
      (js/console.groupEnd msg))))

;; Set the main potok error handler
(reset! st/on-error on-error)

;; We receive a explicit authentication error; this explicitly clears
;; all profile data and redirect the user to the login page. This is
;; here and not in app.main.errors because of circular dependency.
(defmethod ptk/handle-error :authentication
  [_]
  (let [msg (tr "errors.auth.unable-to-login")]
    (st/emit! (du/logout {:capture-redirect true}))
    (ts/schedule 500 (st/emitf (msg/warn msg)))))


;; That are special case server-errors that should be treated
;; differently.
(derive :not-found ::exceptional-state)
(derive :bad-gateway ::exceptional-state)
(derive :service-unavailable ::exceptional-state)

(defmethod ptk/handle-error ::exceptional-state
  [error]
  (ts/schedule
   (st/emitf (rt/assign-exception error))))

;; Error that happens on an active business model validation does not
;; passes an validation (example: profile can't leave a team). From
;; the user perspective a error flash message should be visualized but
;; user can continue operate on the application. Can happen in backend
;; and frontend.
(defmethod ptk/handle-error :validation
  [error]
  (ts/schedule
   (st/emitf
    (msg/show {:content "Unexpected validation error."
              :type :error
              :timeout 3000})))

  ;; Print to the console some debug info.
  (js/console.group "Validation Error:")
  (ex/ignoring
   (js/console.info
    (with-out-str (fpp/pprint (dissoc error :explain)))))

  (when-let [explain (:explain error)]
    (js/console.group "Spec explain:")
    (js/console.log explain)
    (js/console.groupEnd "Spec explain:"))

  (js/console.groupEnd "Validation Error:"))


;; Error on parsing an SVG
;; TODO: looks unused and deprecated
(defmethod ptk/handle-error :svg-parser
  [_]
  (ts/schedule
   (st/emitf
    (msg/show {:content "SVG is invalid or malformed"
              :type :error
              :timeout 3000}))))

;; TODO: should be handled in the event and not as general error handler
(defmethod ptk/handle-error :comment-error
  [_]
  (ts/schedule
   (st/emitf
    (msg/show {:content "There was an error with the comment"
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
                        (dm/str cf/public-uri "js/cljs-runtime/" (:file error))
                        (:line error))]
    (ts/schedule
     (st/emitf
      (msg/show {:content "Internal error: assertion."
                :type :error
                :timeout 3000})))

    ;; Print to the console some debugging info
    (js/console.group message)
    (js/console.info context)
    (js/console.error (with-out-str (expound/printer error)))
    (js/console.groupEnd message)))

;; This happens when the backed server fails to process the
;; request. This can be caused by an internal assertion or any other
;; uncontrolled error.
(defmethod ptk/handle-error :server-error
  [{:keys [data hint] :as error}]
  (let [hint (or hint (:hint data) (:message data))
        info (with-out-str (fpp/pprint (dissoc data :explain)))
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
  (if (instance? ExceptionInfo error)
    (-> error ex-data ptk/handle-error)
    (let [hint (ex-message error)
          msg  (dm/str "Unhandled Internal Error: " hint)]
      (ts/schedule (st/emitf (rt/assign-exception error)))
      (js/console.group msg)
      (ex/ignoring (js/console.error error))
      (js/console.groupEnd msg))))

(defonce uncaught-error-handler
  (letfn [(on-error [event]
            (.preventDefault ^js event)
            (some-> (unchecked-get event "error")
                    (on-unhandled-error)))]
    (.addEventListener glob/window "error" on-error)
    (fn []
      (.removeEventListener glob/window "error" on-error))))
