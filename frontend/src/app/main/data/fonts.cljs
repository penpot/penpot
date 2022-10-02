;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.fonts
  (:require
   ["opentype.js" :as ot]
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.common.media :as cm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.fonts :as fonts]
   [app.main.repo :as rp]
   [app.util.storage :refer [storage]]
   [app.util.webapi :as wa]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General purpose events & IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fonts-fetched
  [fonts]
  (letfn [;; Prepare font to the internal font database format.
          (prepare-font [[id [item :as items]]]
            {:id id
             :name (:font-family item)
             :family (:font-family item)
             :variants (->> items
                            (map prepare-font-variant)
                            (sort-by variant-sort-fn)
                            (vec))})

          (variant-sort-fn [item]
            [(:weight item)
             (if (= "normal" (:style item)) 1 2)])

          (prepare-font-variant [item]
            {:id (str (:font-style item) "-" (:font-weight item))
             :name (str (cm/font-weight->name (:font-weight item))
                        (when (not= "normal" (:font-style item))
                          (str " " (str/capital (:font-style item)))))
             :style (:font-style item)
             :weight (str (:font-weight item))
             ::fonts/woff1-file-id (:woff1-file-id item)
             ::fonts/woff2-file-id (:woff2-file-id item)
             ::fonts/ttf-file-id (:ttf-file-id item)
             ::fonts/otf-file-id (:otf-file-id item)})

          (adapt-font-id [variant]
            (update variant :font-id #(str "custom-" %)))]

    (ptk/reify ::team-fonts-loaded
      ptk/UpdateEvent
      (update [_ state]
        (assoc state :dashboard-fonts (d/index-by :id fonts)))

      ptk/EffectEvent
      (effect [_ _ _]
        (let [fonts (->> fonts
                         (map adapt-font-id)
                         (group-by :font-id)
                         (mapv prepare-font))]
          (fonts/register! :custom fonts))))))

(defn load-team-fonts
  [team-id]
  (ptk/reify ::load-team-fonts
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/query :font-variants {:team-id team-id})
           (rx/map fonts-fetched)))))

(defn process-upload
  "Given a seq of blobs and the team id, creates a ready-to-use fonts
  map with temporal ID's associated to each font entry."
  [blobs team-id]
  (letfn [(prepare [{:keys [font type name data] :as params}]
            (let [family  (or (.getEnglishName ^js font "preferredFamily")
                              (.getEnglishName ^js font "fontFamily"))
                  variant (or (.getEnglishName ^js font "preferredSubfamily")
                              (.getEnglishName ^js font "fontSubfamily"))]
              {:content {:data (js/Uint8Array. data)
                         :name name
                         :type type}
               :font-family (or family "")
               :font-weight (cm/parse-font-weight variant)
               :font-style  (cm/parse-font-style variant)}))

          (join [res {:keys [content] :as font}]
            (let [key-fn   (juxt :font-family :font-weight :font-style)
                  existing (d/seek #(= (key-fn font) (key-fn %)) (vals res))]
              (if existing
                (update res
                        (:id existing)
                        (fn [existing]
                          (-> existing
                              (update :data assoc (:type content) (:data content))
                              (update :names conj (:name content)))))
                (let [tmp-id (uuid/next)]
                  (assoc res tmp-id
                         (-> font
                             (assoc :id tmp-id)
                             (assoc :team-id team-id)
                             (assoc :names #{(:name content)})
                             (assoc :data {(:type content)
                                           (:data content)})
                             (dissoc :content)))))))

          (parse-mtype [ba]
            (let [u8 (js/Uint8Array. ba 0 4)
                  sg (areduce u8 i ret "" (str ret (if (zero? i) "" " ") (.toString (aget u8 i) 8)))]
              (case sg
                "117 124 124 117" "font/otf"
                "0 1 0 0"         "font/ttf"
                "167 117 106 106" "font/woff")))

          (parse-font [{:keys [data] :as params}]
            (try
              (assoc params :font (ot/parse data))
              (catch :default _e
                (log/warn :msg (str/fmt "skipping file %s, unsupported format" (:name params)))
                nil)))

          (read-blob [blob]
            (->> (wa/read-file-as-array-buffer blob)
                 (rx/map (fn [data]
                           {:data data
                            :name (.-name blob)
                            :type (parse-mtype data)}))
                 (rx/mapcat (fn [{:keys [type] :as font}]
                              (if type
                                (rx/of font)
                                (rx/empty))))))]

    (->> (rx/from blobs)
         (rx/mapcat read-blob)
         (rx/map parse-font)
         (rx/filter some?)
         (rx/map prepare)
         (rx/reduce join {}))))

(defn- calculate-family-to-id-mapping
  [existing]
  (reduce #(assoc %1 (:font-family %2) (:font-id %2)) {} (vals existing)))

(defn merge-and-group-fonts
  "Function responsible to merge (and apropriatelly group) incoming
  fonts (processed by `process-upload`) into existing fonts
  in local state, preserving correct font-id references."
  [current-fonts installed-fonts incoming-fonts]
  (loop [famdb  (-> (merge current-fonts installed-fonts)
                    (calculate-family-to-id-mapping))
         items  (vals incoming-fonts)
         result current-fonts]
    (if-let [{:keys [id font-family] :as item} (first items)]
      (let [font-id (or (get famdb font-family)
                        (uuid/next))
            font    (assoc item :font-id font-id)]
        (recur (assoc famdb font-family font-id)
               (rest items)
               (assoc result id font)))
      result)))

(defn rename-and-regroup
  "Function responsible to rename a font in a local state and properly
  regroup it to the appropriate `font-id` having in account current
  fonts and installed fonts."
  [current-fonts id name installed-fonts]
  (let [famdb   (-> (merge current-fonts installed-fonts)
                    (calculate-family-to-id-mapping))
        font-id (or (get famdb name)
                    (uuid/next))]
    (update current-fonts id (fn [font]
                               (-> font
                                   (assoc :font-family name)
                                   (assoc :font-id font-id))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dashboard related events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-font
  [font]
  (ptk/reify ::add-font
    IDeref
    (-deref [_] (select-keys font [:font-family :font-style :font-weight]))

    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-fonts assoc (:id font) font))))

(defn update-font
  [{:keys [id name] :as params}]
  (us/assert ::us/uuid id)
  (us/assert ::us/not-empty-string name)
  (ptk/reify ::update-font
    ptk/UpdateEvent
    (update [_ state]
      ;; Update all variants that has the same font-id with the new
      ;; name in the local state.
      (update state :dashboard-fonts
              (fn [fonts]
                (d/mapm (fn [_ font]
                          (cond-> font
                            (= id (:font-id font))
                            (assoc :font-family name)))
                        fonts))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/mutation! :update-font {:id id :name name :team-id team-id})
             (rx/ignore))))))

(defn delete-font
  "Delete all variants related to the provided `font-id`."
  [font-id]
  (us/assert ::us/uuid font-id)
  (ptk/reify ::delete-font
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-fonts
              (fn [variants]
                (d/removem (fn [[_id variant]]
                             (= (:font-id variant) font-id)) variants))))
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/mutation! :delete-font {:id font-id :team-id team-id})
             (rx/ignore))))))

(defn delete-font-variant
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-font-variants
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-fonts
              (fn [variants]
                (d/removem (fn [[_ variant]]
                             (= (:id variant) id))
                           variants))))
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/mutation! :delete-font-variant {:id id :team-id team-id})
             (rx/ignore))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace related events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-recent-font
  [font]
  (ptk/reify ::add-recent-font
    ptk/UpdateEvent
    (update [_ state]
      (let [recent-fonts      (get-in state [:workspace-data :recent-fonts])
            most-recent-fonts (into [font] (comp (remove #(= font %)) (take 3)) recent-fonts)]
        (assoc-in state [:workspace-data :recent-fonts] most-recent-fonts)))
    ptk/EffectEvent
    (effect [_ state _]
      (let [most-recent-fonts      (get-in state [:workspace-data :recent-fonts])]
        (swap! storage assoc ::recent-fonts most-recent-fonts)))))

(defn load-recent-fonts
  []
  (ptk/reify ::load-recent-fonts
    ptk/UpdateEvent
    (update [_ state]
      (let [saved-recent-fonts (::recent-fonts @storage)]
        (assoc-in state [:workspace-data :recent-fonts] saved-recent-fonts)))))
