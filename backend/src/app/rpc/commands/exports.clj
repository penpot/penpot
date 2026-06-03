;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.exports
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.http :as-alias http]
   [app.http.client :as httpc]
   [app.http.session :as session]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv])
  (:import
   java.time.Duration))

(def ^:private exporter-timeout
  "Timeout for exporter HTTP calls. 5 minutes for large renders."
  (Duration/ofMinutes 5))

(defn- call-exporter
  "Send a POST to the exporter service with Transit-encoded body.
   auth-token  — JWE token string (cookie or temp session)
   Returns the transit-decoded response body map."
  [{:as cfg} auth-token exporter-params]
  (let [uri     (str (u/ensure-path-slash
                      (cf/get :exporter-uri "http://localhost:6061")))
        body    (t/encode-str exporter-params {:type :json})
        headers {"content-type" "application/transit+json"
                 "accept"       "application/transit+json"
                 "cookie"       (str "auth-token=" auth-token)}
        request {:method  :post
                 :uri     uri
                 :headers headers
                 :body    body
                 :timeout exporter-timeout}
        response (httpc/req cfg request {:skip-ssrf-check? true})]
    (when (not= 200 (:status response))
      (ex/raise :type :internal
                :code :exporter-request-failed
                :hint (str "exporter returned status " (:status response))
                :http-status (:status response)))
    (t/decode-str (:body response))))

(defn- resolve-auth-token
  "Returns [auth-token temporary-session? session-id] where:
   - auth-token is the JWE token string to forward to the exporter
   - temporary-session? is true if a temp session was created (needs cleanup)
   - session-id is the temp session DB id (nil if using cookie)"
  [cfg {:keys [::rpc/profile-id] :as params}]
  (let [request    (-> params meta ::http/request)
        auth-token (get-in request [:cookies "auth-token" :value])]
    (if auth-token
      [auth-token false nil]
      ;; No cookie — must be access-token auth. Create a temp session.
      (let [{:keys [id token]} (session/create-session-token cfg profile-id)]
        [token true id]))))

;; ─── Schemas ─────────────────────────────────────────────────────────────

(def ^:private schema:export-entry
  [:map {:title "export-entry"}
   [:file-id   ::sm/uuid]
   [:page-id   ::sm/uuid]
   [:object-id ::sm/uuid]
   [:type      [:keyword {:enum #{:png :jpeg :webp :svg :pdf}}]]
   [:scale     ::sm/number]
   [:suffix    :string]
   [:name      :string]
   [:share-id  {:optional true} ::sm/uuid]])

(def ^:private schema:export-shapes-params
  [:map {:title "export-shapes-params"}
   [:exports  [:vector {:min 1} schema:export-entry]]
   [:wait     {:optional true} :boolean]
   [:name     {:optional true} :string]
   [:is-wasm  {:optional true} :boolean]])

(def ^:private schema:export-frames-entry
  [:map {:title "export-frames-entry"}
   [:file-id   ::sm/uuid]
   [:page-id   ::sm/uuid]
   [:object-id ::sm/uuid]
   [:name      :string]])

(def ^:private schema:export-frames-params
  [:map {:title "export-frames-params"}
   [:exports  [:vector {:min 1} schema:export-frames-entry]]
   [:wait     {:optional true} :boolean]
   [:name     {:optional true} :string]
   [:is-wasm  {:optional true} :boolean]])

(def ^:private schema:export-result
  "Result for wait:true. When wait:false, :uri is absent."
  [:map {:title "export-result"}
   [:id       ::sm/uuid]
   [:name     :string]
   [:filename :string]
   [:uri      {:optional true} :string]
   [:mtype    :string]])

;; ─── RPC Methods ─────────────────────────────────────────────────────────

(sv/defmethod ::export-shapes
  "Export one or more shapes to image files (PNG, JPEG, WebP, SVG).

   Set :wait true for synchronous export (blocks until rendering and upload
   complete, returns download URI). Set :wait false for asynchronous export
   (returns immediately with a resource ID; progress updates flow via the
   existing WebSocket path).

   When exporting a single shape, the response contains a direct download
   URI. When exporting multiple shapes, the response contains a ZIP file
   download URI."
  {::doc/added "2.15"
   ::sm/params schema:export-shapes-params
   ::sm/result schema:export-result}
  [cfg {:keys [exports wait name is-wasm] :as params}]
  (let [[auth-token temp-session? session-id]
        (resolve-auth-token cfg params)

        profile-id (::rpc/profile-id params)
        exporter-params (cond-> {:cmd        :export-shapes
                                 :profile-id profile-id
                                 :wait       (boolean wait)
                                 :exports    exports
                                 :is-wasm    (boolean is-wasm)}
                          (some? name) (assoc :name name))
        result (call-exporter cfg auth-token exporter-params)]

    ;; Clean up temp session for sync exports only.
    ;; For wait:false, the exporter still needs the token for async upload.
    (when (and temp-session? (true? wait))
      (session/delete-session-by-id cfg session-id))

    result))

(sv/defmethod ::export-frames
  "Export one or more frames/boards to a single PDF file.

   Set :wait true for synchronous export (blocks until rendering and upload
   complete, returns download URI). Set :wait false for asynchronous export
   (returns immediately with a resource ID; progress via WebSocket)."
  {::doc/added "2.15"
   ::sm/params schema:export-frames-params
   ::sm/result schema:export-result}
  [cfg {:keys [exports wait name is-wasm] :as params}]
  (let [[auth-token temp-session? session-id]
        (resolve-auth-token cfg params)

        profile-id (::rpc/profile-id params)
        exporter-params (cond-> {:cmd        :export-frames
                                 :profile-id profile-id
                                 :exports    exports
                                 :is-wasm    (boolean is-wasm)}
                          (some? name) (assoc :name name))
        result (call-exporter cfg auth-token exporter-params)]

    (when (and temp-session? (true? wait))
      (session/delete-session-by-id cfg session-id))

    result))
