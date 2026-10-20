;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
   [app.main.data.uploads :as uploads]
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

(def ^:private font-upload-chunk-size
  "Size in bytes of each chunk when uploading font files (10 MiB)."
  (* 1024 1024 10))

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
             :name (cm/font-display-variant (:variant-name item)
                                            (:font-weight item)
                                            (:font-style item))
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
  [team-id]
  (ptk/reify ::fetch-fonts
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-font-variants {:team-id team-id})
           (rx/map fonts-fetched)))))

(defn upload-font-variant
  "Uploads a single font variant item using the chunked upload API.

  For each mime-type in `data`, creates a Blob and uploads it via the
  session-based chunked upload.  Once all sessions are created, calls
  `create-font-variant` with the resulting `:uploads` map so the server
  can assemble the chunks and materialise the final font-variant record.

  Returns an observable that emits the created font-variant."
  [{:keys [data team-id font-id font-family font-weight font-style] :as _item}]
  ;; Upload each mtype as a separate chunked session in parallel, collect
  ;; all [mtype session-id] pairs, then call create-font-variant with :uploads.
  (->> (rx/from (seq data))
       (rx/mapcat (fn [[mtype buffer]]
                    (let [blob (js/Blob. #js [buffer] #js {:type mtype})]
                      (->> (uploads/upload-blob-chunked blob :chunk-size font-upload-chunk-size)
                           (rx/map (fn [{:keys [session-id]}]
                                     [mtype session-id]))))))
       (rx/reduce (fn [acc [mtype session-id]]
                    (assoc acc mtype session-id))
                  {})
       (rx/mapcat (fn [uploads]
                    (rp/cmd! :create-font-variant
                             {:team-id     team-id
                              :font-id     font-id
                              :font-family font-family
                              :font-weight font-weight
                              :font-style  font-style
                              :uploads     uploads})))))

(defn- parse-font-mtype
  "Detects a font mime-type from the first four bytes of an ArrayBuffer.

  Returns the `font/*` mime-type string, or nil when the buffer is
  missing, too short, or carries an unknown signature. Unknown
  signatures are treated as errors by `read-blob`, never silently
  dropped."
  [ba]
  (when (and (some? ba)
             (>= (.-byteLength ^js ba) 4))
    (let [u8 (js/Uint8Array. ba 0 4)
          sg (areduce u8 i ret "" (str ret (if (zero? i) "" " ") (.toString (aget u8 i) 8)))]
      (case sg
        "117 124 124 117" "font/otf"
        "0 1 0 0"         "font/ttf"
        "167 117 106 106" "font/woff"
        "167 117 106 62"  "font/woff2"
        nil))))

(defn- parse-font
  "Associates the opentype.js font object for TTF/OTF/WOFF blobs.

  Takes a `read-blob` result map. Passes `{:error ...}` maps through
  untouched. WOFF2 blobs cannot be parsed by opentype.js, so they keep
  `:font nil` and metadata is derived from the filename later. Any other
  blob is parsed with opentype.js; when parsing fails an
  `{:error filename}` map is returned so the caller can notify the user
  instead of silently dropping the file."
  [{:keys [error data type name] :as params}]
  (cond
    (some? error)
    params

    (= type "font/woff2")
    ;; woff2 cannot be parsed by opentype.js, extract metadata from filename
    (assoc params :font nil)

    :else
    (try
      (assoc params :font (ot/parse data))
      (catch :default _e
        (log/warn :msg (str/fmt "skipping file %s, unsupported format" name))
        {:error name}))))

(defn- prepare-font
  "Builds a ready-to-upload font entry from a parsed blob.

  Never throws for missing font metadata: a nil subfamily (opentype.js
  returns nil when the font carries no English preferred/subfamily
  names, as seen with some Fontshare families) defaults to regular
  weight/style, and missing vertical-metric tables default to no
  height warning."
  [{:keys [font type name data] :as _params}]
  (let [name (or name "")]
    (if (some? font)
      ;; Font was parsed with opentype.js (ttf, otf, woff)
      (let [family          (or (.getEnglishName ^js font "preferredFamily")
                                (.getEnglishName ^js font "fontFamily")
                                "")
            variant         (or (.getEnglishName ^js font "preferredSubfamily")
                                (.getEnglishName ^js font "fontSubfamily")
                                "")

            ;; Vertical metrics determine the baseline in a text and the space between lines of
            ;; text. For historical reasons, there are three pairs of ascender/descender
            ;; values, known as hhea, OS/2 and uSWin metrics. Depending on the font, operating
            ;; system and application a different set will be used to render text on the
            ;; screen. On Mac, Safari and Chrome use the hhea values to render text. Firefox
            ;; will respect the useTypoMetrics setting and will use the OS/2 if it is set.  If
            ;; the useTypoMetrics is not set, Firefox will also use metrics from the hhea
            ;; table. On Windows, all browsers use the usWin metrics, but respect the
            ;; useTypoMetrics setting and if set will use the OS/2 values.

            hhea            (some-> ^js font .-tables .-hhea)
            os2             (some-> ^js font .-tables .-os2)

            hhea-ascender   (some-> ^js hhea .-ascender abs)
            hhea-descender  (some-> ^js hhea .-descender abs)

            win-ascent      (some-> ^js os2 .-usWinAscent abs)
            win-descent     (some-> ^js os2 .-usWinDescent abs)

            os2-ascent      (some-> ^js os2 .-sTypoAscender abs)
            os2-descent     (some-> ^js os2 .-sTypoDescender abs)

            ;; useTypoMetrics can be read from the 7th bit
            f-selection     (some-> ^js os2 .-fsSelection (bit-test 7))

            height-warning? (boolean
                             (and hhea-ascender hhea-descender win-ascent win-descent
                                  (or (not= hhea-ascender win-ascent)
                                      (not= hhea-descender win-descent)
                                      (and f-selection os2-ascent os2-descent
                                           (or (not= hhea-ascender os2-ascent)
                                               (not= hhea-descender os2-descent))))))
            data            (js/Uint8Array. data)]
        {:content {:data data
                   :name name
                   :type type}
         :font-family (or family "")
         :font-weight (cm/parse-font-weight variant)
         :font-style  (cm/parse-font-style variant)
         :variant-name variant
         :height-warning? height-warning?})
      ;; Font could not be parsed (woff2), extract metadata from filename
      (let [base-name       (str/replace name #"\.[^.]+$" "")
            ;; Strip known weight/style tokens and separators to derive family name
            ;; Use word boundaries to avoid matching substrings (e.g. "Boldini" should not match "bold")
            raw-family-name (-> base-name
                                (str/replace #"(?i)(^|[-_\s])(extra\s*black|ultra\s*black|extra\s*bold|ultra\s*bold|semi\s*bold|demi\s*bold|extra\s*light|ultra\s*light|hairline|thin|light|normal|regular|medium|bold|black|heavy|solid|italic)([-_\s]|$)" "$1$3")
                                (str/replace #"[-_\s]+" " ")
                                (str/trim))
            family-name     (if (str/blank? raw-family-name) base-name raw-family-name)
            data            (js/Uint8Array. data)]
        {:content {:data data
                   :name name
                   :type type}
         :font-family family-name
         :font-weight (cm/parse-font-weight base-name)
         :font-style  (cm/parse-font-style base-name)
         :height-warning? false}))))

(defn process-upload
  "Given a seq of blobs and the team id, creates a ready-to-use fonts
  map with temporal ID's associated to each font entry.

  Each font entry's `:data` is a map of `{mtype -> ArrayBuffer}`.  The
  raw `ArrayBuffer` is kept as-is so that `upload-font-variant` can
  wrap it in a `Blob` and hand it directly to `upload-blob-chunked`
  without any intermediate client-side chunking.

  Files that cannot be read, carry an unknown signature, fail
  opentype.js parsing, or fail entry preparation are collected and
  reported with a `bad-font` notification instead of silently dropped,
  so the user always gets feedback. The returned observable never
  errors for per-file failures; it emits the map of successfully
  processed fonts (possibly empty)."
  [blobs team-id]
  (letfn [(join [res {:keys [content] :as font}]
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

          (read-blob [blob]
            (let [blob-name (.-name blob)]
              (->> (wa/read-file-as-array-buffer blob)
                   (rx/map (fn [data]
                             {:data data
                              :name blob-name
                              :type (parse-font-mtype data)}))
                   (rx/catch (fn []
                               (rx/of {:error blob-name})))
                   (rx/mapcat (fn [{:keys [type error] :as font}]
                                (if (or type error)
                                  (rx/of font)
                                  ;; Unknown signature: report it instead of silently dropping it.
                                  (rx/of {:error blob-name})))))))]

    (->> (rx/from blobs)
         (rx/mapcat read-blob)
         (rx/map parse-font)
         (rx/filter some?)
         (rx/reduce (fn [acc item]
                      (if (:error item)
                        (update acc :errors conj (str "'" (:error item) "'"))
                        (try
                          (update acc :fonts #(join % (prepare-font item)))
                          (catch :default cause
                            (log/warn :msg "skipping font file" :cause cause :name (:name item))
                            (update acc :errors conj (str "'" (:name item) "'"))))))
                    {:fonts {} :errors []})
         (rx/mapcat (fn [{:keys [fonts errors]}]
                      (when (seq errors)
                        (st/emit!
                         (ntf/error
                          (if (> (count errors) 1)
                            (tr "errors.bad-font-plural" (str/join ", " errors))
                            (tr "errors.bad-font" (first errors))))))
                      (rx/of fonts))))))

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
        (rx/of (ev/event {::ev/name "add-font"
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
         (rx/of (ev/event {::ev/name "delete-font"
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
         (rx/of (ev/event {::ev/name "delete-font-variant"
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
