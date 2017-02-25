(ns uxbox.tests.helpers
  (:refer-clojure :exclude [await])
  (:require [clj-http.client :as http]
            [buddy.hashers :as hashers]
            [buddy.core.codecs :as codecs]
            [catacumba.serializers :as sz]
            [mount.core :as mount]
            [datoteka.storages :as st]
            [suricatta.core :as sc]
            [uxbox.services.auth :as usa]
            [uxbox.services.users :as usu]
            [uxbox.util.transit :as t]
            [uxbox.migrations :as umg]
            [uxbox.media :as media]
            [uxbox.db :as db]
            [uxbox.config :as cfg]))

(def +base-url+ "http://localhost:5050")

(defn state-init
  [next]
  (let [config (cfg/read-test-config)]
    (-> (mount/only #{#'uxbox.config/config
                      #'uxbox.config/secret
                      #'uxbox.db/datasource
                      #'uxbox.migrations/migrations
                      #'uxbox.media/assets-storage
                      #'uxbox.media/media-storage
                      #'uxbox.media/images-storage
                      #'uxbox.media/thumbnails-storage})
        (mount/swap {#'uxbox.config/config config})
        (mount/start))
    (try
      (next)
      (finally
        (mount/stop)))))

(defn database-reset
  [next]
  (state-init
   (fn []
     (with-open [conn (db/connection)]
       (let [sql (str "SELECT table_name "
                      "  FROM information_schema.tables "
                      " WHERE table_schema = 'public' "
                      "   AND table_name != 'migrations';")
             result (->> (sc/fetch conn sql)
                         (map :table_name))]
         (sc/execute conn (str "TRUNCATE "
                               (apply str (interpose ", " result))
                               " CASCADE;"))))
     (try
       (next)
       (finally
         (st/clear! uxbox.media/media-storage)
         (st/clear! uxbox.media/assets-storage))))))

(defmacro await
  [expr]
  `(try
     (deref ~expr)
     (catch Exception e#
       (.getCause e#))))

(defn- strip-response
  [{:keys [status headers body]}]
  (if (= (get headers "content-type") "application/transit+json")
    [status (-> (codecs/str->bytes body)
                (t/decode))]
    [status body]))

(defn http-get
  ([user uri] (http-get user uri nil))
  ([user uri {:keys [query] :as opts}]
   (let [headers (when user
                   {"Authorization" (str "Token " (usa/generate-token user))})
         params (merge {:headers headers}
                       (when query
                         {:query-params query}))]
     (try
       (strip-response (http/get uri params))
       (catch clojure.lang.ExceptionInfo e
         (strip-response (ex-data e)))))))

(defn http-post
  ([uri params]
   (http-post nil uri params))
  ([user uri {:keys [body] :as params}]
   (let [body (-> (t/encode body)
                  (codecs/bytes->str))
         headers (merge
                  {"content-type" "application/transit+json"}
                  (when user
                    {"Authorization" (str "Token " (usa/generate-token user))}))
         params {:headers headers :body body}]
     (try
       (strip-response (http/post uri params))
       (catch clojure.lang.ExceptionInfo e
         (strip-response (ex-data e)))))))

(defn http-multipart
  [user uri params]
  (let [headers (merge
                  (when user
                    {"Authorization" (str "Token " (usa/generate-token user))}))
        params {:headers headers
                :multipart params}]
    (try
      (strip-response (http/post uri params))
      (catch clojure.lang.ExceptionInfo e
        (strip-response (ex-data e))))))

(defn http-put
  ([uri params]
   (http-put nil uri params))
  ([user uri {:keys [body] :as params}]
   (let [body (-> (t/encode body)
                  (codecs/bytes->str))
         headers (merge
                  {"content-type" "application/transit+json"}
                  (when user
                    {"Authorization" (str "Token " (usa/generate-token user))}))
         params {:headers headers :body body}]
     (try
       (strip-response (http/put uri params))
       (catch clojure.lang.ExceptionInfo e
         (strip-response (ex-data e)))))))

(defn http-delete
  ([uri]
   (http-delete nil uri))
  ([user uri]
   (let [headers (when user
                   {"Authorization" (str "Token " (usa/generate-token user))})
         params {:headers headers}]
     (try
       (strip-response (http/delete uri params))
       (catch clojure.lang.ExceptionInfo e
         (strip-response (ex-data e)))))))

(defn- decode-response
  [{:keys [status headers body] :as response}]
  (if (= (get headers "content-type") "application/transit+json")
    (assoc response :body (-> (codecs/str->bytes body)
                              (t/decode)))
    response))

(defn request
  [{:keys [path method body user headers raw?]
    :or {raw? false}
    :as request}]
  {:pre [(string? path) (keyword? method)]}
  (let [body (if (and body (not raw?))
               (-> (t/encode body)
                   (codecs/bytes->str))
               body)
        headers (cond-> headers
                  body (assoc "content-type" "application/transit+json")
                  raw? (assoc "content-type" "application/octet-stream")
                  user (assoc "authorization"
                              (str "Token " (usa/generate-token user))))
        params {:headers headers :body body}
        uri (str +base-url+ path)]
    (try
      (let [response (case method
                       :get (http/get uri (dissoc params :body))
                       :post (http/post uri params)
                       :put (http/put uri params)
                       :delete (http/delete uri params))]
        (decode-response response))
      (catch clojure.lang.ExceptionInfo e
        (decode-response (ex-data e))))))

(defn create-user
  "Helper for create users"
  [conn i]
  (let [data {:username (str "user" i)
              :password (str "user" i)
              :metadata (str i)
              :fullname (str "User " i)
              :email (str "user" i "@uxbox.io")}]
    (usu/create-user conn data)))

(defmacro try-on
  [& body]
  `(try
     (let [result# (do ~@body)]
       [nil result#])
     (catch Throwable e#
       [e# nil])))

(defn exception?
  [v]
  (instance? Throwable v))

(defn ex-info?
  [v]
  (instance? clojure.lang.ExceptionInfo v))

(defn ex-of-type?
  [e type]
  (let [data (ex-data e)]
    (= type (:type data))))

(defn ex-with-code?
  [e code]
  (let [data (ex-data e)]
    (= code (:code data))))

