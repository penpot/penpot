;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.auth
  "Resolves the caller's session cookie to a real profile id.

  The export commands take `:profile-id` from the request body, which was
  harmless while it only picked a pub/sub topic. The job API can read and
  cancel other people's work, so its ownership comes from the session: the
  token goes to the backend's `get-profile` command, which answers with the
  anonymous profile (`uuid/zero`) when it is not a valid session.

  Results are memoized briefly, so a burst of export calls from one client is
  one round trip rather than one per request."
  (:require
   ["undici" :as http]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [promesa.core :as p]))

(def ^:private cache-ttl-ms 60000)

(defonce ^:private cache (atom {}))

(defn- put-in-cache
  "Stores the resolution by session token and removes expired entries.
  Without cleanup, long-lived exporters accumulate stale entries"
  [cache token profile-id now]
  (-> (into {} (remove (fn [[_ {:keys [expires-at]}]] (<= expires-at now))) cache)
      (assoc token {:profile-id profile-id
                    :expires-at (+ now cache-ttl-ms)})))

(defn- rpc-uri
  []
  (-> (cf/get-internal-uri)
      (u/ensure-path-slash)
      (u/join "api/rpc/command/get-profile")
      (str)))

(defn- fetch-profile-id
  [token]
  (let [uri     (rpc-uri)
        headers #js {"Content-Type" "application/transit+json"
                     "X-Shared-Key" (str "exporter " cf/management-key)
                     "Cookie"       (str "auth-token=" token)}]
    (->> (p/do (http/fetch uri #js {:method "POST" :headers headers :body (t/encode-str {})}))
         (p/mcat (fn [^js resp]
                   (if (= 200 (.-status resp))
                     (.text resp)
                     (p/resolved nil))))
         (p/fmap (fn [body]
                   (some-> body t/decode-str :id)))
         (p/merr (fn [cause]
                   (l/warn :hint "unable to resolve session profile" :uri uri :cause cause)
                   (p/resolved nil))))))

(defn resolve-profile-id
  "Promise of the authenticated profile id, or nil for an anonymous or absent
  session."
  [token]
  (if (nil? token)
    (p/resolved nil)
    (let [{:keys [profile-id expires-at]} (get @cache token)]
      (if (and expires-at (> expires-at (js/Date.now)))
        (p/resolved profile-id)
        (->> (fetch-profile-id token)
             (p/fmap (fn [profile-id]
                       (let [profile-id (when (and profile-id (not= uuid/zero profile-id)) profile-id)]
                         (swap! cache put-in-cache token profile-id (js/Date.now))
                         profile-id))))))))

(defn require-profile-id
  "Like `resolve-profile-id`, but rejects anonymous callers."
  [token]
  (->> (resolve-profile-id token)
       (p/mcat (fn [profile-id]
                 (if profile-id
                   (p/resolved profile-id)
                   (ex/raise :type :authentication
                             :code :authentication-required
                             :hint "no valid session for this request"))))))

(defn check-owner!
  "Raises unless `profile-id` owns `job`."
  [job profile-id]
  (when (or (nil? job)
            (not= (str (:profile-id job)) (str profile-id)))
    (ex/raise :type :not-found
              :code :object-not-found
              :hint "job does not exist"))
  job)
