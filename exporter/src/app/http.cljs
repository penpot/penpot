(ns app.http
  (:require
   [promesa.core :as p]
   [lambdaisland.glogi :as log]
   [app.browser :as bwr]
   [app.http.screenshot :refer [bitmap-handler]]
   [reitit.core :as r]
   ["koa" :as koa]
   ["http" :as http])
  (:import
   goog.Uri))

(defn query-params
  "Given goog.Uri, read query parameters into Clojure map."
  [^goog.Uri uri]
  (let [q (.getQueryData uri)]
    (->> q
         (.getKeys)
         (map (juxt keyword #(.get q %)))
         (into {}))))

(defn- match
  [router ctx]
  (let [uri (.parse Uri (unchecked-get ctx "originalUrl"))]
    (when-let [match (r/match-by-path router (.getPath uri))]
      (let [qparams (query-params uri)
            params  {:path (:path-params match) :query qparams}]
        (assoc match
               :params params
               :query-params qparams)))))

(defn- handle-response
  [ctx {:keys [body headers status] :or {headers {} status 200}}]
  (run! (fn [[k v]] (.set ^js ctx k v)) headers)
  (set! (.-body ^js ctx) body)
  (set! (.-status ^js ctx) status)
  nil)

(defn- wrap-handler
  [f extra]
  (fn [ctx]
    (let [cookies (unchecked-get ctx "cookies")
          request (assoc extra :ctx ctx :cookies cookies)]
      (-> (p/do! (f request))
          (p/then (fn [rsp]
                    (when (map? rsp)
                      (handle-response ctx rsp))))))))

(def routes
  [["/export"
    ["/bitmap" {:handler bitmap-handler}]]])

(defn- router-handler
  [router]
  (fn [{:keys [ctx] :as req}]
    (let [route   (match router ctx)
          request (assoc req
                         :route route
                         :params (:params route))
          handler (get-in route [:data :handler])]
      (if (and route handler)
        (handler request)
        {:status 404
         :body "Not found"}))))

(defn start!
  [extra]
  (log/info :msg "starting http server" :port 6061)
  (let [router   (r/router routes)
        instance (doto (new koa)
                   (.use (-> (router-handler router)
                             (wrap-handler extra))))
        server   (.createServer http (.callback instance))]
    (.listen server 6061)
    (p/resolved server)))

(defn stop!
  [server]
  (p/create (fn [resolve]
              (.close server (fn []
                               (log/info :msg "shutdown http server")
                               (resolve))))))

