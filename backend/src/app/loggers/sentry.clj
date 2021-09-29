;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.loggers.sentry
  "A mattermost integration for error reporting."
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.util.async :as aa]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import
   io.sentry.Scope
   io.sentry.IHub
   io.sentry.Hub
   io.sentry.NoOpHub
   io.sentry.protocol.User
   io.sentry.SentryOptions
   io.sentry.SentryLevel
   io.sentry.ScopeCallback))

(defonce enabled (atom true))

(defn- parse-context
  [event]
  (reduce-kv
   (fn [acc k v]
     (cond
       (= k :id)         (assoc acc k (uuid/uuid v))
       (= k :profile-id) (assoc acc k (uuid/uuid v))
       (str/blank? v)    acc
       :else             (assoc acc k v)))
   {}
   (:context event)))

(defn- parse-event
  [event]
  (assoc event :context (parse-context event)))

(defn- build-sentry-options
  [cfg]
  (let [version (:base cf/version)]
    (doto (SentryOptions.)
      (.setDebug (:debug cfg false))
      (.setTracesSampleRate (:traces-sample-rate cfg 1.0))
      (.setDsn (:dsn cfg))
      (.setServerName (cf/get :host))
      (.setEnvironment (cf/get :tenant))
      (.setAttachServerName true)
      (.setAttachStacktrace (:attach-stack-trace cfg false))
      (.setRelease (str "backend@" (if (= version "0.0.0") "develop" version))))))

(defn handle-event
  [^IHub shub event]
  (letfn [(set-user! [^Scope scope {:keys [context] :as event}]
            (let [user (User.)]
              (.setIpAddress ^User user ^String (:ip-addr context))
              (when-let [pid (:profile-id context)]
                (.setId ^User user ^String (str pid)))
              (.setUser scope ^User user)))

          (set-level! [^Scope scope]
            (.setLevel scope SentryLevel/ERROR))

          (set-context! [^Scope scope {:keys [context] :as event}]
            (let [uri (str (cf/get :public-uri) "/dbg/error-by-id/" (:id context))]
              (.setContexts scope "detailed_error_uri" ^String uri))
            (when-let [vers (:frontend-version event)]
              (.setContexts scope "frontend_version" ^String vers))
            (when-let [puri (:public-uri event)]
              (.setContexts scope "public_uri" ^String (str puri)))
            (when-let [uagent (:user-agent context)]
              (.setContexts scope "user_agent" ^String uagent))
            (when-let [tenant (:tenant event)]
              (.setTag scope "tenant" ^String tenant))
            (when-let [type (:error-type context)]
              (.setTag scope "error_type" ^String  (str type)))
            (when-let [code (:error-code context)]
              (.setTag scope "error_code" ^String (str code)))
            )

          (capture [^Scope scope {:keys [context error] :as event}]
            (let [msg   (str (:message error) "\n\n"

                             "======================================================\n"
                             "=================== Params ===========================\n"
                             "======================================================\n"

                             (:params context) "\n"

                             (when (:explain context)
                               (str "======================================================\n"
                                    "=================== Explain ==========================\n"
                                    "======================================================\n"
                                    (:explain context) "\n"))

                             (when (:data context)
                               (str "======================================================\n"
                                    "=================== Error Data =======================\n"
                                    "======================================================\n"
                                    (:data context) "\n"))

                             (str "======================================================\n"
                                  "=================== Stack Trace ======================\n"
                                  "======================================================\n"
                                  (:trace error))

                             "\n")]
              (set-user! scope event)
              (set-level! scope)
              (set-context! scope event)
              (.captureMessage ^IHub shub msg)
              ))
          ]
    ;; (clojure.pprint/pprint event)

    (when @enabled
      (.withScope ^IHub shub (reify ScopeCallback
                               (run [_ scope]
                                 (->> event
                                      (parse-event)
                                      (capture scope))))))

    ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Error Listener
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::receiver any?)
(s/def ::dsn ::cf/sentry-dsn)
(s/def ::trace-sample-rate ::cf/sentry-trace-sample-rate)
(s/def ::attach-stack-trace ::cf/sentry-attach-stack-trace)
(s/def ::debug ::cf/sentry-debug)

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req-un [::wrk/executor ::db/pool ::receiver]
          :opt-un [::dsn ::trace-sample-rate ::attach-stack-trace]))

(defmethod ig/init-key ::reporter
  [_ {:keys [receiver dsn executor] :as cfg}]
  (l/info :msg "initializing sentry reporter" :dsn dsn)
  (let [opts   (build-sentry-options cfg)
        shub   (if dsn
                 (Hub. ^SentryOptions opts)
                 (NoOpHub/getInstance))
        output (a/chan (a/sliding-buffer 128)
                       (filter #(= (:level %) "error")))]
    (receiver :sub output)
    (a/go-loop []
      (let [event (a/<! output)]
        (if (nil? event)
          (do
            (l/info :msg "stoping error reporting loop")
            (.close ^IHub shub))
          (do
            (a/<! (aa/with-thread executor (handle-event shub event)))
            (recur)))))
    output))

(defmethod ig/halt-key! ::reporter
  [_ output]
  (when output
    (a/close! output)))
