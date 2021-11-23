;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.errors
  "Generic error handling"
  (:require
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.sentry :as sentry]
   [app.main.store :as st]
   [app.util.router :as rt]
   [app.util.timers :as ts]
   [cljs.pprint :refer [pprint]]
   [cuerdas.core :as str]
   [expound.alpha :as expound]
   [potok.core :as ptk]))

(defn on-error
  "A general purpose error handler."
  [error]
  (cond
    (instance? ExceptionInfo error)
    (-> error sentry/capture-exception ex-data ptk/handle-error)

    (map? error)
    (ptk/handle-error error)

    :else
    (let [hint (ex-message error)
          msg  (str "Internal Error: " hint)]
      (sentry/capture-exception error)
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
  (ts/schedule (st/emitf (du/logout))))


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
;; user can continue operate on the application.
(defmethod ptk/handle-error :validation
  [error]
  (ts/schedule
   (st/emitf
    (dm/show {:content "Unexpected validation error."
              :type :error
              :timeout 3000})))

  ;; Print to the console some debug info.
  (js/console.group "Validation Error:")
  (ex/ignoring
   (js/console.info
    (with-out-str
      (pprint (dissoc error :explain))))
   (when-let [explain (:explain error)]
     (js/console.error explain)))
  (js/console.groupEnd "Validation Error:"))


;; Error on parsing an SVG
(defmethod ptk/handle-error :svg-parser
  [_]
  (ts/schedule
   (st/emitf
    (dm/show {:content "SVG is invalid or malformed"
              :type :error
              :timeout 3000}))))

(defmethod ptk/handle-error :comment-error
  [_]
  (ts/schedule
   (st/emitf
    (dm/show {:content "There was an error with the comment"
              :type :error
              :timeout 3000}))))

;; This is a pure frontend error that can be caused by an active
;; assertion (assertion that is preserved on production builds). From
;; the user perspective this should be treated as internal error.
(defmethod ptk/handle-error :assertion
  [{:keys [data stack message hint context] :as error}]
  (let [message (or message hint)
        message (str "Internal Assertion Error: " message)
        context (str/fmt "ns: '%s'\nname: '%s'\nfile: '%s:%s'"
                              (:ns context)
                              (:name context)
                              (str cf/public-uri "js/cljs-runtime/" (:file context))
                              (:line context))]
    (ts/schedule
     (st/emitf
      (dm/show {:content "Internal error: assertion."
                :type :error
                :timeout 3000})))

    ;; Print to the console some debugging info
    (js/console.group message)
    (js/console.info context)
    (js/console.groupCollapsed "Stack Trace")
    (js/console.info stack)
    (js/console.groupEnd "Stack Trace")
    (js/console.error (with-out-str (expound/printer data)))
    (js/console.groupEnd message)))

;; This happens when the backed server fails to process the
;; request. This can be caused by an internal assertion or any other
;; uncontrolled error.
(defmethod ptk/handle-error :server-error
  [{:keys [data hint] :as error}]
  (let [hint (or hint (:hint data) (:message data))
        info (with-out-str (pprint (dissoc data :explain)))
        expl (:explain data)
        msg  (str "Internal Server Error: " hint)]

    (ts/schedule
     (st/emitf
      (dm/show {:content "Something wrong has happened (on backend)."
                :type :error
                :timeout 3000})))

    (js/console.group msg)
    (js/console.info info)
    (when expl (js/console.error expl))
    (js/console.groupEnd msg)))

(defn on-unhandled-error
  [error]
  (if (instance? ExceptionInfo error)
    (-> error sentry/capture-exception ex-data ptk/handle-error)
    (let [hint (ex-message error)
          msg  (str "Unhandled Internal Error: " hint)]
      (sentry/capture-exception error)
      (ts/schedule (st/emitf (rt/assign-exception error)))
      (js/console.group msg)
      (ex/ignoring (js/console.error error))
      (js/console.groupEnd msg))))

(defonce uncaught-error-handler
  (letfn [(on-error [event]
            (.preventDefault ^js event)
            (some-> (unchecked-get event "error")
                    (on-unhandled-error)))]
    (.addEventListener js/window "error" on-error)
    (fn []
      (.removeEventListener js/window "error" on-error))))
