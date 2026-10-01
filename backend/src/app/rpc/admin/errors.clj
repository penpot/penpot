;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.errors
  "Admin error-report commands, served through `/api/admin/methods`.

  They mirror `get-error-reports` / `get-error-report` (which stay
  token-only for integrations) for session callers, reusing their
  query and schemas. Guards live in wrap-authentication
  (the 'superuser' permission), not here — except
  get-shared-error-report, anonymous by design: its share token
  is the only credential."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as-alias sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.error-reports :as error-reports]
   [app.rpc.doc :as doc]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [cuerdas.core :as str]
   [yetti.response :as yres]))

(defn read-report
  "Read one error report by id, or nil when missing.

  Same shaping as `::get-error-report`, shared with the stateless
  share-link RPC command so both paint the same data."
  [cfg id]
  (when-let [report (db/get-by-id cfg :server-error-report id {::db/check-deleted false})]
    (let [content (db/decode-transit-pgobject (:content report))]
      (-> report
          (dissoc :content)
          (merge content)
          (update :source error-reports/source->name)
          (assoc :kind (or (:kind content) (:origin content)))
          (assoc :version (:version content))
          (d/without-nils)))))

(sv/defmethod ::get-error-reports
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params error-reports/schema:get-error-reports-params
   ::sm/result error-reports/schema:get-error-reports-result}
  [cfg params]
  (let [limit            (min (or (:limit params) error-reports/default-limit)
                              error-reports/max-limit)
        params           (assoc params :limit (inc limit))
        [sql & sql-args] (error-reports/build-list-query params)
        rows             (db/exec! cfg (into [sql] sql-args))]
    (if (seq rows)
      (let [items      (->> (take limit rows)
                            (mapv #(-> %
                                       (update :source error-reports/source->name)
                                       d/without-nils)))
            last-item  (peek items)
            has-more?  (> (count rows) limit)]
        {:items      items
         :next-since (when has-more? (:created-at last-item))
         :next-id    (when has-more? (:id last-item))})
      {:items []})))

(sv/defmethod ::get-error-report
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params error-reports/schema:get-error-report-params
   ::sm/result error-reports/schema:error-report}
  [cfg {:keys [id]}]
  (if-let [report (read-report cfg id)]
    report
    (ex/raise :type :not-found
              :code :report-not-found
              :hint (str "error report " id " not found"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARE LINKS (stateless)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A share link is a JWE token minted by `::create-error-report-share`
;; and consumed by `::get-shared-error-report` (`GET
;; /api/admin/methods/get-shared-error-report?token=<token>`).
;; Nothing is stored: the token carries the report id, the creator
;; and the expiry, sealed with the server key. There is no
;; revocation; protection is a short TTL plus a scope of exactly one
;; report. Rotating `secret-key` kills every link at once.
(def share-iss
  "Issuer marker for error-report share tokens."
  "error-report-share")

(def ^:private share-default-ttl-hours
  4)

(def ^:private share-max-ttl-hours
  24)

;; Short identifying rows go first in this fixed order; every other
;; key follows alphabetically so a new content key never goes
;; missing.
(def ^:private share-head-rows
  [[:id "Id"]
   [:created-at "Date"]
   [:source "Source"]])

(defn- share-drop-separators
  "Drop filler separator lines (runs of `=`) from a value. They bloat
  shared reports and carry no information; stored reports keep them."
  [text]
  (->> (str/split text #"\r?\n")
       (remove #(re-find #"^\s*={10,}\s*$" %))
       (str/join "\n")))

(defn- share-text-value
  [v]
  (cond
    (string? v) (share-drop-separators v)
    (or (uuid? v) (ct/inst? v)) (str v)
    :else (pr-str v)))

(defn- share-title
  "Shared title, `# Report: <hint>` (first line only, so a
  multi-line hint cannot break the heading). Without a hint it falls
  back to a plain label. Mirrors the web detail page, where the hint
  is the title and never renders as a row."
  [report]
  (let [hint  (when (string? (:hint report))
                (first (str/split (:hint report) #"\r?\n")))
        label (if (str/blank? hint) "Error report" (str "Report: " (str/trim hint)))]
    (str "# " label)))

(defn- share-fence
  "Fence for a section body. Steps up to four backticks when the body
  already holds a fence, so the block never closes early."
  [text]
  (if (str/includes? text "```") "````" "```"))

(defn render-shared-report
  "Render `report` as plain text for shared links and `curl`.

  Same shape as the old `/dbg/error` templates: a title, compact
  one-line rows, then the long bodies under `##` headings in fenced
  blocks, `trace` and `context` first, printed verbatim. No field parsing: new content keys show up on
  their own. `expires-at` feeds the footer so the reader knows the
  link dies."
  [report expires-at]
  (let [known?    #{:id :created-at :source :hint}
        rest-keys (->> (keys report)
                       (remove known?)
                       (sort-by name))
        row?      (fn [k]
                    (let [v (get report k)]
                      (and (some? v)
                           (not (re-find #"[\r\n]" (share-text-value v))))))
        rows      (filter row? rest-keys)
        sections  (remove row? rest-keys)
        sections  (concat (filter #{:trace} sections)
                          (filter #{:context} sections)
                          (remove #{:trace :context} sections))
        lines     (into [(share-title report) ""]
                        (keep (fn [[k label]]
                                (when-some [v (get report k)]
                                  (str label ": " (share-text-value v))))
                              share-head-rows))
        lines     (into lines
                        (map (fn [k]
                               (str (name k) ": " (share-text-value (get report k))))
                             rows))
        lines     (into (conj (vec lines) "")
                        (mapcat (fn [k]
                                  (let [text  (share-text-value (get report k))
                                        fence (share-fence text)]
                                    [(str "## " (str/capital (name k)))
                                     ""
                                     fence
                                     text
                                     fence
                                     ""]))
                                sections))]
    (str/join "\n" (conj (vec lines)
                         "---"
                         (str "Shared link, expires " (str expires-at)
                              ". Single report, no account access.")))))

(defn- verify-share-token
  "Verify a share-link token. Returns `[report-id expires-at]`, or
  nil when the token is bad, expired, foreign or malformed. Never
  throws and never logs the token."
  [cfg token]
  (when (seq (some-> token str/trim))
    (let [claims    (ex/ignoring
                     (tokens/verify cfg {:token (str/trim (str token))
                                         :iss share-iss}))
          report-id (:report-id claims)]
      (when (uuid? report-id)
        [report-id (:exp claims)]))))

(def schema:create-error-report-share-params
  [:map {:title "create-error-report-share-params"}
   [:id ::sm/uuid]
   [:ttl-hours {:optional true} [:int {:min 1 :max share-max-ttl-hours}]]])

(def schema:create-error-report-share-result
  [:map {:title "create-error-report-share-result"}
   [:url ::sm/text]
   [:expires-at ct/schema:inst]])

(sv/defmethod ::create-error-report-share
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:create-error-report-share-params
   ::sm/result schema:create-error-report-share-result}
  [cfg {:keys [id ttl-hours ::rpc/profile-id]}]
  (let [ttl-hours (or ttl-hours share-default-ttl-hours)]
    (when-not (read-report cfg id)
      (ex/raise :type :not-found
                :code :report-not-found
                :hint (str "error report " id " not found")))
    (let [expires-at (ct/plus (ct/now) (ct/duration {:hours ttl-hours}))
          token      (tokens/generate cfg {:iss share-iss
                                           :report-id id
                                           :created-by profile-id
                                           :exp expires-at})]
      (audit/insert cfg {:name "create-error-report-share"
                         :type "action"
                         :profile-id profile-id
                         :context {:triggered-by "admin-panel"
                                   :report-id id
                                   :ttl-hours ttl-hours}})
      {:url (str (cf/get-public-uri "api/admin/methods/get-shared-error-report")
                 "?token=" token)
       :expires-at expires-at})))

(def ^:private share-not-found
  "Same 404 for a bad, expired or foreign token and for a missing
  report, so probing tokens leaks nothing."
  {::yres/status 404
   ::yres/headers {"content-type" "text/plain"}
   ::yres/body "not found"})

(def schema:get-shared-error-report-params
  ;; Any string goes (even missing/blank): anything unverifiable
  ;; answers the same 404 below instead of a validation error, so
  ;; probing tokens leaks nothing.
  [:map {:title "get-shared-error-report-params"}
   [:token {:optional true} :string]])

;; NOTE: no `::sm/result` on purpose: like the SSE commands, this
;; returns a raw response function instead of data, so there is no
;; transit/JSON shape to validate.
(sv/defmethod ::get-shared-error-report
  {::doc/added "2.20"
   ::rpc/auth false
   ::sm/params schema:get-shared-error-report-params}
  [cfg {:keys [token]}]
  (fn [_request]
    (let [[report-id expires-at] (verify-share-token cfg token)
          report                 (when (some? report-id)
                                   (read-report cfg report-id))]
      (if (and (some? report) (some? expires-at))
        {::yres/status  200
         ::yres/headers {"content-type"  "text/markdown; charset=utf-8"
                         "cache-control" "no-store"}
         ::yres/body    (render-shared-report report expires-at)}
        share-not-found))))
