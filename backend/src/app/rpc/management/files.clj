;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.management.files
  "Read-only file content for the nexus file-security pipeline.

  The single method `get-file-for-analysis` returns the semantic
  projection of one file (pages, frames, text, links, interactions):
  small, neutral data the decision model consumes. Full file binaries
  never cross this boundary. Every call leaves an audit event like any
  other management call, so content reads stay traceable; the volume
  scales with analyzed files, and the nexus consumer ignores the event."
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.text :as txt]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as doc]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def projection-version 1)

;; Bounds for the data that crosses to nexus on every sweep. The
;; projection stays small by construction; anything past the caps sets
;; `:truncated?` instead of growing the payload.
(def ^:const max-text-chars 30000)
(def ^:const max-links 1000)
(def ^:const max-interactions 2000)

(defn- page-shapes
  "All shapes of one page with their page id attached."
  [{:keys [id objects]}]
  (let [page-id (str id)]
    (->> (vals objects)
         (filter map?)
         (map #(assoc % :page-id page-id)))))

(defn- shape-text
  "Plain text of a text shape, or nil when it has none. Never throws:
  corrupt content skips the shape instead of failing the file."
  [shape]
  (when (and (= :text (:type shape))
             (some? (:content shape)))
    (let [text (ex/ignoring (txt/content->text (:content shape)))]
      (when-not (str/blank? text)
        text))))

(defn- open-url?
  [{:keys [action-type url]}]
  (and (= :open-url action-type)
       (not (str/blank? url))))

(defn file->projection
  "Turn decoded file data into the neutral projection the decision
  model consumes. Pure: takes the `:data` map, returns plain data
  with all ids as strings."
  [{:keys [pages pages-index]}]
  (let [shapes  (mapcat page-shapes
                        (keep #(get pages-index %) pages))
        frames  (into []
                      (comp (filter #(= :frame (:type %)))
                            (map (fn [{:keys [id name page-id]}]
                                   (d/without-nils
                                    {:id (str id)
                                     :page-id page-id
                                     :name name}))))
                      shapes)
        texts   (into []
                      (comp (map (juxt :id :page-id shape-text))
                            (remove #(nil? (nth % 2)))
                            (map (fn [[id page-id text]]
                                   {:shape-id (str id)
                                    :page-id page-id
                                    :text text})))
                      shapes)
        ;; Texts are capped by total characters, the unit the model
        ;; bills; links and interactions by count.
        texts   (loop [acc []
                       chars 0
                       [item & rest] texts]
                  (cond
                    (nil? item)
                    {:items acc :truncated? false}

                    (> (+ chars (count (:text item))) max-text-chars)
                    {:items acc :truncated? true}

                    :else
                    (recur (conj acc item)
                           (+ chars (count (:text item)))
                           rest)))
        inter   (into []
                      (comp (mapcat (fn [shape]
                                      (->> (:interactions shape)
                                           (filter map?)
                                           (filter :action-type)
                                           (filter :event-type)
                                           (map #(assoc % :shape-id (:id shape)
                                                        :page-id (:page-id shape))))))
                            (map (fn [{:keys [shape-id page-id event-type action-type destination url]}]
                                   (d/without-nils
                                    {:shape-id (str shape-id)
                                     :page-id page-id
                                     :event (d/name event-type)
                                     :action (d/name action-type)
                                     :target (or url (some-> destination str))}))))
                      shapes)
        links   (into []
                      (comp (mapcat (fn [shape]
                                      (->> (:interactions shape)
                                           (filter map?)
                                           (filter open-url?)
                                           (map #(assoc % :shape-id (:id shape)
                                                        :page-id (:page-id shape))))))
                            (map (fn [{:keys [shape-id page-id url]}]
                                   {:shape-id (str shape-id)
                                    :page-id page-id
                                    :url url})))
                      shapes)]
    {:version projection-version
     :pages (mapv (fn [page-id]
                    (let [page (get pages-index page-id)]
                      (d/without-nils
                       {:id (str page-id)
                        :name (:name page)})))
                  pages)
     :frames frames
     :texts texts
     :links (if (> (count links) max-links)
              {:items (subvec links 0 max-links) :truncated? true}
              {:items links :truncated? false})
     :interactions (if (> (count inter) max-interactions)
                     {:items (subvec inter 0 max-interactions) :truncated? true}
                     {:items inter :truncated? false})}))

(def ^:private sql:has-share-link
  "SELECT EXISTS (SELECT 1 FROM share_link WHERE file_id = ?) AS shared;")

(def ^:private sql:get-team-id
  "SELECT team_id FROM project WHERE id = ?;")

(def ^:private
  schema:get-file-for-analysis-params
  [:map {:title "get-file-for-analysis-params"}
   [:file-id ::sm/uuid]])

(def ^:private schema:projection-page
  [:map
   [:id :string]
   [:name {:optional true} :string]])

(def ^:private schema:projection-frame
  [:map
   [:id :string]
   [:page-id :string]
   [:name {:optional true} :string]])

(def ^:private schema:projection-text
  [:map
   [:shape-id :string]
   [:page-id :string]
   [:text :string]])

(def ^:private schema:projection-link
  [:map
   [:shape-id :string]
   [:page-id :string]
   [:url :string]])

(def ^:private schema:projection-interaction
  [:map
   [:shape-id :string]
   [:page-id :string]
   [:event :string]
   [:action :string]
   [:target {:optional true} :string]])

(def ^:private schema:projection
  [:map {:title "file-projection"}
   [:version ::sm/int]
   [:pages [:vector schema:projection-page]]
   [:frames [:vector schema:projection-frame]]
   [:texts [:map
            [:items [:vector schema:projection-text]]
            [:truncated? :boolean]]]
   [:links [:map
            [:items [:vector schema:projection-link]]
            [:truncated? :boolean]]]
   [:interactions [:map
                   [:items [:vector schema:projection-interaction]]
                   [:truncated? :boolean]]]])

(def ^:private
  schema:get-file-for-analysis-result
  [:map {:title "get-file-for-analysis-result"}
   [:id ::sm/uuid]
   [:revn ::sm/int]
   [:modified-at ::ct/inst]
   [:project-id ::sm/uuid]
   [:team-id ::sm/uuid]
   [:has-share-link :boolean]
   [:projection-version ::sm/int]
   [:projection schema:projection]])

(sv/defmethod ::get-file-for-analysis
  "Get the semantic projection of one file for security analysis.

  Read-only: returns the file identity, revision, share state and the
  neutral projection (pages, frames, text, links, interactions).
  Called by nexus with the shared-key identity; takes no profile.
  Every call leaves an audit event like any other management call."
  {::doc/added "2.20"
   ::sm/params schema:get-file-for-analysis-params
   ::sm/result schema:get-file-for-analysis-result
   ::rpc/auth false}
  [cfg {:keys [file-id]}]
  ;; `realize?` loads the whole object tree eagerly: the projection
  ;; walks every shape, so lazy pointers (no `load-fn` bound here)
  ;; would fail on first touch. Same choice as the file exporter.
  (let [file       (bfc/get-file cfg file-id :realize? true)
        shared     (:shared (db/exec-one! cfg [sql:has-share-link file-id]))
        team-id    (:team-id (db/exec-one! cfg [sql:get-team-id (:project-id file)]))]
    {:id (:id file)
     :revn (:revn file)
     :modified-at (:modified-at file)
     :project-id (:project-id file)
     :team-id team-id
     :has-share-link (boolean shared)
     :projection-version projection-version
     :projection (file->projection (:data file))}))
