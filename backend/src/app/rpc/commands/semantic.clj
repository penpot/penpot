;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.semantic
  "Internal API that returns a pruned, semantic projection of a file.

  The projection keeps only the material a decision model needs to judge a
  design: page and frame names, visible text, external links and
  navigations. It drops geometry, assets, components, tokens and every
  library, because a raw Penpot file is mostly noise for that judgment and
  the noise is documented to hurt a System One model.

  It is meant for background security analysis and for prototyping with
  System One models; see `mem:backend/semantic-file`."
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.schema :as sm]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as feat.fdata]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SEMANTIC PROJECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def projection-version
  "Bumped whenever the projection shape changes, so stored analyses can be
  invalidated and recomputed."
  1)

(def ^:private navigation-action-types
  #{:navigate :open-overlay :toggle-overlay :close-overlay})

(defn- shape-text
  "Plain visible text of a text shape, or nil when the shape is not text or
  holds no text."
  [shape]
  (when (cfh/text-shape? shape)
    (let [text (some-> (:content shape) txt/content->text)]
      (when-not (str/blank? text)
        text))))

(defn- shape-label
  "Best human label for a shape: its text when it has any, its name otherwise."
  [shape]
  (or (shape-text shape) (:name shape)))

(defn- open-url-links
  [shape]
  (->> (:interactions shape)
       (keep (fn [{:keys [action-type url]}]
               (when (and (= :open-url action-type)
                          (not (str/blank? url)))
                 {:text (shape-label shape)
                  :url url})))))

(defn- navigations
  [objects shape]
  (->> (:interactions shape)
       (keep (fn [{:keys [action-type destination]}]
               (when (and (contains? navigation-action-types action-type)
                          (some? destination))
                 {:text (shape-label shape)
                  :action (name action-type)
                  :target (or (:name (get objects destination))
                              (str destination))})))))

(defn- project-signals
  "Semantic signals gathered from a collection of shapes, in tree order."
  [objects shapes]
  (let [texts  (->> shapes (keep shape-text) distinct vec)
        links  (->> shapes (mapcat open-url-links) distinct vec)
        navs   (->> shapes (mapcat #(navigations objects %)) distinct vec)
        images (count (filter cfh/image-shape? shapes))]
    (cond-> {:text texts}
      (seq links)   (assoc :links links)
      (seq navs)    (assoc :navigations navs)
      (pos? images) (assoc :images images))))

(defn- has-signals?
  [signals]
  (or (seq (:text signals))
      (seq (:links signals))
      (seq (:navigations signals))
      (pos? (:images signals 0))))

(defn- project-frame
  [objects frame]
  (assoc (project-signals objects (cfh/get-children-with-self objects (:id frame)))
         :name (:name frame)))

(defn- project-page
  [page]
  (let [objects    (d/nilv (:objects page) {})
        root       (get objects uuid/zero)
        tops       (->> (:shapes root) (map #(get objects %)) (remove nil?))
        frames     (->> tops
                        (filter cfh/frame-shape?)
                        ;; Component main instances are library content, not
                        ;; design content: they must not reach the model.
                        (remove :main-instance)
                        (map #(project-frame objects %))
                        (filter has-signals?)
                        (vec))
        loose-tops (remove cfh/frame-shape? tops)
        loose      (project-signals objects
                                    (mapcat #(cfh/get-children-with-self objects (:id %))
                                            loose-tops))]
    (cond-> {:name (:name page)
             :frames frames}
      (seq (:text loose))        (assoc :text (:text loose))
      (seq (:links loose))       (assoc :links (:links loose))
      (seq (:navigations loose)) (assoc :navigations (:navigations loose)))))

(defn- project-stats
  [pages]
  (let [frames (mapcat :frames pages)
        sum    (fn [coll f] (transduce (map f) + 0 coll))]
    {:pages       (count pages)
     :frames      (count frames)
     :text-nodes  (+ (sum frames #(count (:text %)))
                     (sum pages  #(count (:text %))))
     :links       (+ (sum frames #(count (:links %)))
                     (sum pages  #(count (:links %))))
     :navigations (+ (sum frames #(count (:navigations %)))
                     (sum pages  #(count (:navigations %))))
     :images      (sum frames #(get % :images 0))}))

(defn file->projection
  "Build the semantic projection of a file. Pure: only reads `file`."
  [file]
  (let [fdata (d/nilv (:data file) {})
        pages (->> (:pages fdata)
                   (keep #(get-in fdata [:pages-index %]))
                   (map project-page)
                   (remove (fn [page]
                             (and (empty? (:frames page))
                                  (empty? (:text page))
                                  (empty? (:links page))
                                  (empty? (:navigations page)))))
                   (vec))]
    {:projection-version projection-version
     :file-id            (:id file)
     :file-name          (:name file)
     :pages              pages
     :stats              (project-stats pages)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:link
  [:map {:title "SemanticLink"}
   [:text [:maybe :string]]
   [:url :string]])

(def schema:navigation
  [:map {:title "SemanticNavigation"}
   [:text [:maybe :string]]
   [:action :string]
   [:target :string]])

(def schema:frame
  [:map {:title "SemanticFrame"}
   [:name :string]
   [:text [:vector :string]]
   [:links {:optional true} [:vector schema:link]]
   [:navigations {:optional true} [:vector schema:navigation]]
   [:images {:optional true} ::sm/int]])

(def schema:page
  [:map {:title "SemanticPage"}
   [:name :string]
   [:frames [:vector schema:frame]]
   [:text {:optional true} [:vector :string]]
   [:links {:optional true} [:vector schema:link]]
   [:navigations {:optional true} [:vector schema:navigation]]])

(def ^:private
  schema:get-semantic-file-params
  [:map {:title "get-semantic-file"}
   [:id ::sm/uuid]])

(def ^:private
  schema:get-semantic-file-result
  [:map {:title "get-semantic-file-result"}
   [:projection-version ::sm/int]
   [:file-id ::sm/uuid]
   [:file-name :string]
   [:pages [:vector schema:page]]
   [:stats
    [:map
     [:pages ::sm/int]
     [:frames ::sm/int]
     [:text-nodes ::sm/int]
     [:links ::sm/int]
     [:navigations ::sm/int]
     [:images ::sm/int]]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RPC METHOD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(sv/defmethod ::get-semantic-file
  "Retrieve a pruned, semantic view of a file: pages, frames, visible text,
  external links and navigations, with geometry, assets, components, tokens
  and libraries stripped. Only authenticated users with read access."
  {::doc/added "2.20"
   ::rpc/id-type :file
   ::sm/params schema:get-semantic-file-params
   ::sm/result schema:get-semantic-file-result}
  [cfg {:keys [::rpc/profile-id id]}]
  (db/run!
   cfg
   (fn [cfg]
     (let [perms (perms/get-file-read-permissions cfg profile-id id)]
       (files/check-read-permissions! perms)
       (->> (bfc/get-file cfg id :read-only? true)
            (feat.fdata/realize cfg)
            (file->projection))))))
