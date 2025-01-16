;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.fonts
  (:require
   ["opentype.js" :as ot]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.media :as cm]
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.notifications :as ntf]
   [app.main.fonts :as fonts]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [app.util.storage :as storage]
   [app.util.webapi :as wa]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

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

    (ptk/reify ::fonts-loaded
      ptk/UpdateEvent
      (update [_ state]
        (assoc state :fonts (d/index-by :id fonts)))

      ptk/EffectEvent
      (effect [_ _ _]
        (let [fonts (->> fonts
                         (map adapt-font-id)
                         (group-by :font-id)
                         (mapv prepare-font))]
          (fonts/register! :custom fonts))))))

(defn fetch-fonts
  []
  (ptk/reify ::load-team-fonts
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :get-font-variants {:team-id team-id})
             (rx/map fonts-fetched))))))

(defn process-upload
  "Given a seq of blobs and the team id, creates a ready-to-use fonts
  map with temporal ID's associated to each font entry."
  [blobs team-id]
  (letfn [(prepare [{:keys [font type name data] :as params}]
            (let [family          (or (.getEnglishName ^js font "preferredFamily")
                                      (.getEnglishName ^js font "fontFamily"))
                  variant         (or (.getEnglishName ^js font "preferredSubfamily")
                                      (.getEnglishName ^js font "fontSubfamily"))

                 ;; Vertical metrics determine the baseline in a text and the space between lines of
                 ;; text. For historical reasons, there are three pairs of ascender/descender
                 ;; values, known as hhea, OS/2 and uSWin metrics. Depending on the font, operating
                 ;; system and application a different set will be used to render text on the
                 ;; screen. On Mac, Safari and Chrome use the hhea values to render text. Firefox
                 ;; will respect the useTypoMetrics setting and will use the OS/2 if it is set.  If
                 ;; the useTypoMetrics is not set, Firefox will also use metrics from the hhea
                 ;; table. On Windows, all browsers use the usWin metrics, but respect the
                 ;; useTypoMetrics setting and if set will use the OS/2 values.

                  hhea-ascender   (abs (-> ^js font .-tables .-hhea .-ascender))
                  hhea-descender  (abs (-> ^js font .-tables .-hhea .-descender))

                  win-ascent      (abs (-> ^js font .-tables .-os2 .-usWinAscent))
                  win-descent     (abs (-> ^js font .-tables .-os2 .-usWinDescent))

                  os2-ascent      (abs (-> ^js font .-tables .-os2 .-sTypoAscender))
                  os2-descent     (abs (-> ^js font .-tables .-os2 .-sTypoDescender))

                  ;; useTypoMetrics can be read from the 7th bit
                  f-selection     (-> ^js font .-tables .-os2 .-fsSelection (bit-test 7))

                  height-warning? (or (not= hhea-ascender win-ascent)
                                      (not= hhea-descender win-descent)
                                      (and f-selection (or
                                                        (not= hhea-ascender os2-ascent)
                                                        (not= hhea-descender os2-descent))))]

              {:content {:data (js/Uint8Array. data)
                         :name name
                         :type type}
               :font-family (or family "")
               :font-weight (cm/parse-font-weight variant)
               :font-style  (cm/parse-font-style variant)
               :height-warning? height-warning?}))

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
                 (rx/catch (fn []
                             (rx/of {:error (.-name blob)})))
                 (rx/mapcat (fn [{:keys [type, error] :as font}]
                              (if (or type error)
                                (rx/of font)
                                (rx/empty))))))]

    (let [fonts (->> (rx/from blobs)
                     (rx/mapcat read-blob))
          errors (->> fonts
                      (rx/filter #(some? (:error %)))
                      (rx/reduce (fn [acc font]
                                   (conj acc (str "'" (:error font) "'")))
                                 []))]

      (rx/subscribe errors
                    #(when
                      (not-empty %)
                       (st/emit!
                        (ntf/error
                         (if (> (count %) 1)
                           (tr "errors.bad-font-plural" (str/join ", " %))
                           (tr "errors.bad-font" (first %)))))))
      (->> fonts
           (rx/filter #(nil? (:error %)))
           (rx/map parse-font)
           (rx/filter some?)
           (rx/map prepare)
           (rx/reduce join {})))))

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
    ptk/UpdateEvent
    (update [_ state]
      (update state :fonts assoc (:id font) font))

    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/of (ptk/data-event ::ev/event {::ev/name "add-font"
                                           :team-id team-id
                                           :font-id (:id font)
                                           :font-family (:font-family font)
                                           :font-style (:font-style font)
                                           :font-weight (:font-weight font)}))))))

(defn update-font
  [{:keys [id name] :as params}]
  (dm/assert! (uuid? id))
  (dm/assert! (string? name))
  (ptk/reify ::update-font
    ptk/UpdateEvent
    (update [_ state]
      ;; Update all variants that has the same font-id with the new
      ;; name in the local state.
      (update state :fonts update-vals (fn [font]
                                         (cond-> font
                                           (= id (:font-id font))
                                           (assoc :font-family name)))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :update-font {:id id :name name :team-id team-id})
             (rx/ignore))))))

(defn delete-font
  "Delete all variants related to the provided `font-id`."
  [font-id]
  (dm/assert! (uuid? font-id))
  (ptk/reify ::delete-font
    ev/Event
    (-data [_]
      {:id font-id})

    ptk/UpdateEvent
    (update [_ state]
      (update state :fonts
              (fn [variants]
                (d/removem (fn [[_id variant]]
                             (= (:font-id variant) font-id)) variants))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/concat
         (->> (rp/cmd! :delete-font {:id font-id :team-id team-id})
              (rx/ignore))
         (rx/of (ptk/data-event ::ev/event {::ev/name "delete-font"
                                            :team-id team-id
                                            :font-id font-id})))))))

(defn delete-font-variant
  [id]
  (dm/assert! (uuid? id))
  (ptk/reify ::delete-font-variants
    ptk/UpdateEvent
    (update [_ state]
      (update state :fonts
              (fn [variants]
                (d/removem (fn [[_ variant]]
                             (= (:id variant) id))
                           variants))))
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/concat
         (->> (rp/cmd! :delete-font-variant {:id id :team-id team-id})
              (rx/ignore))
         (rx/of (ptk/data-event ::ev/event {::ev/name "delete-font-variant"
                                            :id id
                                            :team-id team-id})))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace related events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- update-recent-font
  "Moves the font/font to the top of the list of recents and then truncates up to 4"
  [state file-id font]
  (let [xform (comp
               (remove #(= font %))
               (take 3))]
    (update state file-id #(into [font] xform %))))

(defn add-recent-font
  [font]
  (ptk/reify ::add-recent-font
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)]
        (update state :recent-fonts update-recent-font file-id font)))

    ptk/EffectEvent
    (effect [_ state _]
      (let [recent-fonts (:recent-fonts state)]
        (swap! storage/user assoc :recent-fonts recent-fonts)))))
