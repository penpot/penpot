;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.clipboard
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.grid-layout :as gslg]
   [app.common.logic.libraries :as cll]
   [app.common.schema :as sm]
   [app.common.transit :as t]
   [app.common.types.component :as ctc]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.shape.text :as types.text]
   [app.common.types.text :as txt]
   [app.common.types.typography :as ctt]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as-alias dps]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwtxt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.errors]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.util.clipboard :as clipboard]
   [app.util.code-gen.markup-svg :as svg]
   [app.util.code-gen.style-css :as css]
   [app.util.globals :as ug]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.text.content :as tc]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

(defn copy-selected
  []
  (letfn [(sort-selected [state data]
            (let [selected (dsh/lookup-selected state)
                  objects  (dsh/lookup-page-objects state)

                  ;; Narrow the objects map so it contains only relevant data for
                  ;; selected and its parents
                  objects  (cfh/selected-subtree objects selected)
                  selected (->> (ctst/sort-z-index objects selected)
                                (reverse)
                                (into (d/ordered-set)))]

              (assoc data :selected selected)))

          (fetch-image [entry]
            (let [url (cf/resolve-file-media entry)]
              (->> (http/send! {:method :get
                                :uri url
                                :response-type :blob})
                   (rx/map :body)
                   (rx/mapcat wapi/read-file-as-data-url)
                   (rx/map #(assoc entry :data %)))))

          ;; Prepare the shape object. Mainly needed for image shapes
          ;; for retrieve the image data and convert it to the
          ;; data-url.
          (prepare-object [objects parent-frame-id obj]
            (let [obj     (maybe-translate obj objects parent-frame-id)
                  ;; Texts can have different fills for pieces of the text
                  imgdata (concat
                           (->> (or (:position-data obj) [obj])
                                (mapcat :fills)
                                (keep :fill-image))
                           (->> (:strokes obj)
                                (keep :stroke-image))
                           (when (cfh/image-shape? obj)
                             [(:metadata obj)])
                           (when (:fill-image obj)
                             [(:fill-image obj)]))]

              (if (seq imgdata)
                (->> (rx/from imgdata)
                     (rx/mapcat fetch-image)
                     (rx/reduce conj [])
                     (rx/map (fn [images]
                               (assoc obj ::images images))))
                (rx/of obj))))

          (collect-variants [state shape]
            (let [page-id (:current-page-id state)
                  data    (dsh/lookup-file-data state)
                  objects (-> (dsh/get-page data page-id)
                              (get :objects))

                  components (cfv/find-variant-components data objects (:id shape))]
              (into {} (map (juxt :id :variant-properties) components))))


          ;; Collects all the items together and split images into a
          ;; separated data structure for a more easy paste process.
          ;; Also collects the variant properties of the copied variants


          (collect-data [state result {:keys [id ::images] :as item}]
            (cond-> result
              :always
              (update :objects assoc id (dissoc item ::images))

              (some? images)
              (update :images into images)

              (ctc/is-variant-container? item)
              (update :variant-properties merge (collect-variants state item))))

          (maybe-translate [shape objects parent-frame-id]
            (if (= parent-frame-id uuid/zero)
              shape
              (let [frame (get objects parent-frame-id)]
                (gsh/translate-to-frame shape frame))))

          ;; When copying an instance that is nested inside another one, we need to
          ;; advance the shape refs to one or more levels of remote mains.
          (advance-copies [state selected data]
            (let [file      (dsh/lookup-file state)
                  libraries (:files state)
                  ;; FIXME
                  page      (dsh/lookup-page state)
                  heads     (mapcat #(ctn/get-child-heads (:objects data) %) selected)]
              (update data :objects
                      #(reduce (partial advance-copy file libraries page)
                               %
                               heads))))

          (advance-copy [file libraries page objects shape]
            (if (and (ctc/instance-head? shape) (not (ctc/main-instance? shape)))
              (let [level-delta (ctn/get-nesting-level-delta (:objects page) shape uuid/zero)]
                (if (pos? level-delta)
                  (reduce (partial advance-shape file libraries page level-delta)
                          objects
                          (cfh/get-children-with-self objects (:id shape)))
                  objects))
              objects))

          (advance-shape [file libraries page level-delta objects shape]
            (let [new-shape-ref (ctf/advance-shape-ref file page libraries shape level-delta {:include-deleted? true})
                  container     (ctn/make-container page :page)
                  new-touched   (ctf/get-touched-from-ref-chain-until-target-ref container libraries shape new-shape-ref)]
              (cond-> objects
                (and (some? new-shape-ref) (not= new-shape-ref (:shape-ref shape)))
                (-> (assoc-in [(:id shape) :shape-ref] new-shape-ref)
                    (assoc-in [(:id shape) :touched] new-touched)))))

          (on-copy-error [error]
            (js/console.error "clipboard blocked:" error)
            (rx/empty))]

    (ptk/reify ::copy-selected
      ptk/WatchEvent
      (watch [_ state _]
        (let [text (wapi/get-current-selected-text)]
          (if-not (str/empty? text)
            (try
              (clipboard/to-clipboard text)
              (catch :default e
                (on-copy-error e)))

            (let [objects  (dsh/lookup-page-objects state)
                  selected (->> (dsh/lookup-selected state)
                                (cfh/clean-loops objects))
                  features (-> (get state :features)
                               (set/difference cfeat/frontend-only-features))

                  file-id  (:current-file-id state)
                  frame-id (cfh/common-parent-frame objects selected)
                  file     (dsh/lookup-file state file-id)
                  version  (get file :version)

                  initial  {:type :copied-shapes
                            :features features
                            :version version
                            :file-id file-id
                            :selected selected
                            :objects {}
                            :images #{}}

                  shapes   (->> (cfh/selected-with-children objects selected)
                                (keep (d/getf objects)))]

              ;; The clipboard API doesn't handle well asynchronous calls because it expects to use
              ;; the clipboard in an user interaction. If you do an async call the callback is outside
              ;; the thread of the UI and so Safari blocks the copying event.
              ;; We use the API `ClipboardItem` that allows promises to be passed and so the event
              ;; will wait for the promise to resolve and everything should work as expected.
              ;; This only works in the current versions of the browsers.
              (if (some? (unchecked-get ug/global "ClipboardItem"))
                (let [resolve-data-promise
                      (p/create
                       (fn [resolve reject]
                         (->> (rx/from shapes)
                              (rx/merge-map (partial prepare-object objects frame-id))
                              (rx/reduce (partial collect-data state) initial)
                              (rx/map (partial sort-selected state))
                              (rx/map (partial advance-copies state selected))
                              (rx/map #(t/encode-str % {:type :json-verbose}))
                              (rx/map #(wapi/create-blob % "text/plain"))
                              (rx/subs! resolve reject))))]
                  (->> (rx/from (clipboard/to-clipboard-promise "text/plain" resolve-data-promise))
                       (rx/catch on-copy-error)
                       (rx/ignore)))

                ;; FIXME: this is to support Firefox versions below 116 that don't support
                ;; `ClipboardItem` after the version 116 is less common we could remove this.
                ;; https://caniuse.com/?search=ClipboardItem
                (->> (rx/from shapes)
                     (rx/merge-map (partial prepare-object objects frame-id))
                     (rx/reduce (partial collect-data state) initial)
                     (rx/map (partial sort-selected state))
                     (rx/map (partial advance-copies state selected))
                     (rx/map #(t/encode-str % {:type :json-verbose}))
                     (rx/map clipboard/to-clipboard)
                     (rx/catch on-copy-error)
                     (rx/ignore))))))))))

(declare ^:private paste-transit-shapes)
(declare ^:private paste-transit-props)
(declare ^:private paste-html-text)
(declare ^:private paste-text)
(declare ^:private paste-image)
(declare ^:private paste-svg-text)
(declare ^:private paste-shapes)

(def ^:private default-options
  #js {:decodeTransit t/decode-str
       :allowHTMLPaste (features/active-feature? @st/state "text-editor/v2-html-paste")})

(defn create-paste-from-blob
  [in-viewport?]
  (fn [blob]
    (let [type (.-type blob)
          result (cond
                   (= type "image/svg+xml")
                   (->> (rx/from (.text blob))
                        (rx/map paste-svg-text))

                   (some #(= type %) clipboard/image-types)
                   (rx/of (paste-image blob))

                   (= type "text/html")
                   (->> (rx/from (.text blob))
                        (rx/map paste-html-text))

                   (= type "application/transit+json")
                   (->> (rx/from (.text blob))
                        (rx/map (fn [text]
                                  (let [transit-data (t/decode-str text)]
                                    (assoc transit-data :in-viewport in-viewport?))))
                        (rx/map paste-transit-shapes))

                   :else
                   (->> (rx/from (.text blob))
                        (rx/map paste-text)))]
      result)))

(def default-paste-from-blob (create-paste-from-blob false))

(defn paste-from-clipboard
  "Perform a `paste` operation using the Clipboard API."
  []
  (ptk/reify ::paste-from-clipboard
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (clipboard/from-navigator default-options)
           (rx/mapcat default-paste-from-blob)
           (rx/take 1)))))

(defn paste-from-event
  "Perform a `paste` operation from user emmited event."
  [event in-viewport?]
  (ptk/reify ::paste-from-event
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects     (dsh/lookup-page-objects state)
            edit-id     (dm/get-in state [:workspace-local :edition])
            is-editing? (and edit-id (= :text (get-in objects [edit-id :type])))]

        ;; Some paste events can be fired while we're editing a text
        ;; we forbid that scenario so the default behaviour is executed
        (if is-editing?
          (rx/empty)
          (->> (clipboard/from-synthetic-clipboard-event event default-options)
               (rx/mapcat (create-paste-from-blob in-viewport?))))))))

(defn copy-selected-svg
  []
  (ptk/reify ::copy-selected-svg
    ptk/EffectEvent
    (effect [_ state _]
      (let [objects         (dsh/lookup-page-objects state)
            selected        (->> (dsh/lookup-selected state)
                                 (ctst/sort-z-index objects)
                                 (mapv (d/getf objects)))
            parent-frame-id (cfh/common-parent-frame objects selected)

            maybe-translate
            #(if (= parent-frame-id uuid/zero) %
                 (gsh/translate-to-frame % (get objects parent-frame-id)))

            shapes          (mapv maybe-translate selected)
            svg-formatted   (svg/generate-formatted-markup objects shapes)]
        (clipboard/to-clipboard svg-formatted)))))

(defn copy-selected-css
  []
  (ptk/reify ::copy-selected-css
    ptk/EffectEvent
    (effect [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            selected (->> (dsh/lookup-selected state) (mapv (d/getf objects)))
            css (css/generate-style objects selected selected {:with-prelude? false})]
        (clipboard/to-clipboard css)))))

(defn copy-selected-css-nested
  []
  (ptk/reify ::copy-selected-css-nested
    ptk/EffectEvent
    (effect [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            selected (->> (dsh/lookup-selected state)
                          (cfh/selected-with-children objects)
                          (mapv (d/getf objects)))
            css (css/generate-style objects selected selected {:with-prelude? false})]
        (clipboard/to-clipboard css)))))

(defn copy-selected-text
  []
  (ptk/reify ::copy-selected-text
    ptk/EffectEvent
    (effect [_ state _]
      (let [selected (dsh/lookup-selected state)
            objects  (dsh/lookup-page-objects state)

            text-shapes
            (->> (cfh/selected-with-children objects selected)
                 (keep (d/getf objects))
                 (filter cfh/text-shape?))

            selected (into (d/ordered-set) (map :id) text-shapes)

            ;; Narrow the objects map so it contains only relevant data for
            ;; selected and its parents
            objects  (cfh/selected-subtree objects selected)
            selected (->> (ctst/sort-z-index objects selected)
                          (into (d/ordered-set)))

            text
            (->> selected
                 (map
                  (fn [id]
                    (let [shape (get objects id)]
                      (-> shape :content txt/content->text))))
                 (str/join "\n"))]

        (clipboard/to-clipboard text)))))

(defn copy-selected-props
  []
  (ptk/reify ::copy-selected-props
    ptk/WatchEvent
    (watch [_ state _]
      (letfn [(fetch-image [entry]
                (let [url (cf/resolve-file-media entry)]
                  (->> (http/send! {:method :get
                                    :uri url
                                    :response-type :blob})
                       (rx/map :body)
                       (rx/mapcat wapi/read-file-as-data-url)
                       (rx/map #(assoc entry :data %)))))

              (resolve-images [data]
                (let [images
                      (concat
                       (->> data :props :fills (keep :fill-image))
                       (->> data :props :strokes (keep :stroke-image)))]

                  (if (seq images)
                    (->> (rx/from images)
                         (rx/mapcat fetch-image)
                         (rx/reduce conj #{})
                         (rx/map #(assoc data :images %)))
                    (rx/of data))))

              (on-copy-error [error]
                (js/console.error "clipboard blocked:" error)
                (rx/empty))]

        (let [selected (dsh/lookup-selected state)]
          (if (> (count selected) 1)
            ;; If multiple items are selected don't do anything
            (rx/empty)

            (let [selected (->> (dsh/lookup-selected state) first)
                  objects  (dsh/lookup-page-objects state)]
              (when-let [shape (get objects selected)]
                (let [props (cts/extract-props shape)
                      features (-> (get state :features)
                                   (set/difference cfeat/frontend-only-features))
                      version  (-> (dsh/lookup-file state)
                                   (get :version))

                      copy-data {:type :copied-props
                                 :features features
                                 :version version
                                 :props props
                                 :images #{}}]

                  ;; The clipboard API doesn't handle well asynchronous calls because it expects to use
                  ;; the clipboard in an user interaction. If you do an async call the callback is outside
                  ;; the thread of the UI and so Safari blocks the copying event.
                  ;; We use the API `ClipboardItem` that allows promises to be passed and so the event
                  ;; will wait for the promise to resolve and everything should work as expected.
                  ;; This only works in the current versions of the browsers.
                  (if (some? (unchecked-get ug/global "ClipboardItem"))
                    (let [resolve-data-promise
                          (p/create
                           (fn [resolve reject]
                             (->> (rx/of copy-data)
                                  (rx/mapcat resolve-images)
                                  (rx/map #(t/encode-str % {:type :json-verbose}))
                                  (rx/map #(wapi/create-blob % "text/plain"))
                                  (rx/subs! resolve reject))))]

                      (->> (rx/from (clipboard/to-clipboard-promise "text/plain" resolve-data-promise))
                           (rx/catch on-copy-error)
                           (rx/ignore)))
                    ;; FIXME: this is to support Firefox versions below 116 that don't support
                    ;; `ClipboardItem` after the version 116 is less common we could remove this.
                    ;; https://caniuse.com/?search=ClipboardItem
                    (->> (rx/of copy-data)
                         (rx/mapcat resolve-images)
                         (rx/map #(clipboard/to-clipboard (t/encode-str % {:type :json-verbose})))
                         (rx/catch on-copy-error)
                         (rx/ignore))))))))))))

(defn paste-selected-props
  []
  (ptk/reify ::paste-selected-props
    ptk/WatchEvent
    (watch [_ state _]
      (when-not (-> state :workspace-global :read-only?)
        (letfn [(decode-entry [entry]
                  (-> entry t/decode-str paste-transit-props))

                (on-error [cause]
                  (let [data (ex-data cause)]
                    (if (:not-implemented data)
                      (rx/of (ntf/warn (tr "errors.clipboard-not-implemented")))
                      (js/console.error "Clipboard error:" cause))
                    (rx/empty)))]

          (->> (clipboard/from-navigator default-options)
               (rx/mapcat #(.text %))
               (rx/map decode-entry)
               (rx/take 1)
               (rx/catch on-error)))))))

(defn- selected-frame? [state]
  (let [selected (dsh/lookup-selected state)
        objects  (dsh/lookup-page-objects state)]

    (and (= 1 (count selected))
         (= :frame (get-in objects [(first selected) :type])))))

(defn- get-tree-root-shapes [tree]
  ;; This fn gets a map of shapes and finds what shapes are parent of the rest
  (let [shapes-in-tree (vals tree)
        shape-ids (keys tree)
        parent-ids (set (map #(:parent-id %) shapes-in-tree))]
    (->> shape-ids
         (filter #(contains? parent-ids %)))))

(defn- any-same-frame-from-selected? [state frame-ids]
  (let [selected (first (dsh/lookup-selected state))]
    (< 0 (count (filter #(= % selected) frame-ids)))))

(defn- frame-same-size?
  [paste-obj frame-obj]
  (and
   (= (:heigth (:selrect (first (vals paste-obj))))
      (:heigth (:selrect frame-obj)))
   (= (:width (:selrect (first (vals paste-obj))))
      (:width (:selrect frame-obj)))))

(def ^:private
  schema:paste-data-shapes
  [:map {:title "paste-data-shapes"}
   [:type [:= :copied-shapes]]
   [:features ::sm/set-of-strings]
   [:version :int]
   [:file-id ::sm/uuid]
   [:selected ::sm/set-of-uuid]
   [:objects
    [:map-of ::sm/uuid :map]]
   [:images [:set :map]]
   [:position {:optional true} ::gpt/point]])

(def ^:private
  schema:paste-data-props
  [:map {:title "paste-data-props"}
   [:type [:= :copied-props]]
   [:features ::sm/set-of-strings]
   [:version :int]
   [:props
    ;; todo type the properties
    [:map-of :keyword :any]]])

(def schema:paste-data
  [:multi {:title "paste-data" :dispatch :type}
   [:copied-shapes schema:paste-data-shapes]
   [:copied-props schema:paste-data-props]])

(def paste-data-valid?
  (sm/lazy-validator schema:paste-data))

(defn- paste-transit-shapes
  [{:keys [images] :as pdata}]
  (letfn [(upload-media [file-id imgpart]
            (->> (http/send! {:uri (:data imgpart)
                              :response-type :blob
                              :method :get})
                 (rx/map :body)
                 (rx/map
                  (fn [blob]
                    {:name (:name imgpart)
                     :file-id file-id
                     :content blob
                     :is-local true}))
                 (rx/mapcat (partial rp/cmd! :upload-file-media-object))
                 (rx/map #(assoc % :prev-id (:id imgpart)))))]

    (ptk/reify ::paste-transit-shapes
      ptk/WatchEvent
      (watch [_ state _]
        (let [file-id  (:current-file-id state)
              features (get state :features)]

          (when-not (paste-data-valid? pdata)
            (ex/raise :type :validation
                      :code :invalid-paste-data
                      :hibt "invalid paste data found"))

          (cfeat/check-paste-features! features (:features pdata))

          (case (:type pdata)
            :copied-shapes
            (if (= file-id (:file-id pdata))
              (let [pdata (assoc pdata :images [])]
                (rx/of (paste-shapes pdata)))
              (->> (rx/from images)
                   (rx/merge-map (partial upload-media file-id))
                   (rx/reduce conj [])
                   (rx/map #(assoc pdata :images %))
                   (rx/map paste-shapes)))
            nil))))))

(defn- paste-transit-props
  [pdata]

  (letfn [(upload-media [file-id imgpart]
            (->> (http/send! {:uri (:data imgpart)
                              :response-type :blob
                              :method :get})
                 (rx/map :body)
                 (rx/map
                  (fn [blob]
                    {:name (:name imgpart)
                     :file-id file-id
                     :content blob
                     :is-local true}))
                 (rx/mapcat (partial rp/cmd! :upload-file-media-object))
                 (rx/map #(vector (:id imgpart) %))))

          (update-image-data
            [pdata media-map]
            (update
             pdata :props
             (fn [props]
               (-> props
                   (d/update-when
                    :fills
                    (fn [fills]
                      (mapv (fn [fill]
                              (cond-> fill
                                (some? (:fill-image fill))
                                (update-in [:fill-image :id] #(get media-map % %))))
                            fills)))
                   (d/update-when
                    :strokes
                    (fn [strokes]
                      (mapv (fn [stroke]
                              (cond-> stroke
                                (some? (:stroke-image stroke))
                                (update-in [:stroke-image :id] #(get media-map % %))))
                            strokes)))))))

          (upload-images
            [file-id pdata]
            (->> (rx/from (:images pdata))
                 (rx/merge-map (partial upload-media file-id))
                 (rx/reduce conj {})
                 (rx/map (partial update-image-data pdata))))]

    (ptk/reify ::paste-transit-props
      ptk/WatchEvent
      (watch [_ state _]
        (let [features (get state :features)
              selected (dsh/lookup-selected state)]

          (when (paste-data-valid? pdata)
            (cfeat/check-paste-features! features (:features pdata))
            (case (:type pdata)
              :copied-props

              (rx/concat
               (->> (rx/of pdata)
                    (rx/mapcat (partial upload-images (:current-file-id state)))
                    (rx/map
                     #(dwsh/update-shapes
                       selected
                       (fn [shape objects] (cts/patch-props shape (:props pdata) objects))
                       {:with-objects? true})))
               (rx/of (ptk/data-event :layout/update {:ids selected})))
              ;;
              (rx/empty))))))))

(defn paste-shapes
  [{in-viewport? :in-viewport :as pdata}]
  (letfn [(translate-media [mdata media-idx attr]
            (let [id   (-> (get mdata attr) :id)
                  mobj (get media-idx id)]
              (if mobj
                (update mdata attr assoc :id (:id mobj))
                mdata)))

          (add-obj? [chg]
            (= (:type chg) :add-obj))

          (process-rchange-shape [obj media-idx]
            (let [translate-fill-image   #(translate-media % media-idx :fill-image)
                  translate-stroke-image #(translate-media % media-idx :stroke-image)
                  translate-fills        #(mapv translate-fill-image %)
                  translate-strokes      #(mapv translate-stroke-image %)
                  process-text-node      #(d/update-when % :fills translate-fills)]

              (-> obj
                  (update :fills translate-fills)
                  (update :strokes translate-strokes)
                  (d/update-when :content #(txt/transform-nodes process-text-node %))
                  (d/update-when :position-data #(mapv process-text-node %)))))

          ;; Analyze the rchange and replace staled media and
          ;; references to the new uploaded media-objects.
          (process-rchange [media-idx change]
            (if (add-obj? change)
              (update change :obj process-rchange-shape media-idx)
              change))

          (calculate-paste-position [state pobjects selected position]
            (let [page-objects         (dsh/lookup-page-objects state)
                  selected-objs        (map (d/getf pobjects) selected)
                  first-selected-obj   (first selected-objs)
                  page-selected        (dsh/lookup-selected state)
                  wrapper              (gsh/shapes->rect selected-objs)
                  orig-pos             (gpt/point (:x1 wrapper) (:y1 wrapper))
                  frame-id             (first page-selected)
                  frame-object         (get page-objects frame-id)
                  base                 (cfh/get-base-shape page-objects page-selected)
                  index                (cfh/get-position-on-parent page-objects (:id base))
                  tree-root            (get-tree-root-shapes pobjects)
                  only-one-root-shape? (and
                                        (< 1 (count pobjects))
                                        (= 1 (count tree-root)))]

              (cond
                (selected-frame? state)

                (if (or (any-same-frame-from-selected? state (keys pobjects))
                        (and only-one-root-shape?
                             (frame-same-size? pobjects (first tree-root))))
                  ;; Paste next to selected frame, if selected is itself or of the same size as the copied
                  (let [selected-frame-obj (get page-objects (first page-selected))
                        parent-id          (:parent-id base)
                        paste-x            (+ (:width selected-frame-obj) (:x selected-frame-obj) 50)
                        paste-y            (:y selected-frame-obj)
                        delta              (gpt/subtract (gpt/point paste-x paste-y) orig-pos)]

                    [parent-id delta index])

                  ;; Paste inside selected frame otherwise
                  (let [selected-frame-obj (get page-objects (first page-selected))
                        origin-frame-id (:frame-id first-selected-obj)
                        origin-frame-object (get page-objects origin-frame-id)

                        margin-x (-> (- (:width origin-frame-object) (+ (:x wrapper) (:width wrapper)))
                                     (min (- (:width frame-object) (:width wrapper))))

                        margin-y  (-> (- (:height origin-frame-object) (+ (:y wrapper) (:height wrapper)))
                                      (min (- (:height frame-object) (:height wrapper))))

                        ;; Pasted objects mustn't exceed the selected frame x limit
                        paste-x (if (> (+ (:width wrapper) (:x1 wrapper)) (:width frame-object))
                                  (+ (- (:x frame-object) (:x orig-pos)) (- (:width frame-object) (:width wrapper) margin-x))
                                  (:x frame-object))

                        ;; Pasted objects mustn't exceed the selected frame y limit
                        paste-y (if (> (+ (:height wrapper) (:y1 wrapper)) (:height frame-object))
                                  (+ (- (:y frame-object) (:y orig-pos)) (- (:height frame-object) (:height wrapper) margin-y))
                                  (:y frame-object))

                        delta (if (= origin-frame-id uuid/zero)
                                ;; When the origin isn't in a frame the result is pasted in the center.
                                (gpt/subtract (gsh/shape->center frame-object) (grc/rect->center wrapper))
                                ;; When pasting from one frame to another frame the object
                                ;; position must be limited to container boundaries. If
                                ;; the pasted object doesn't fit we try to:
                                ;;
                                ;; - Align it to the limits on the x and y axis
                                ;; - Respect the distance of the object to the right
                                ;;   and bottom in the original frame
                                (gpt/point paste-x paste-y))]
                    [frame-id delta (dec (count (:shapes selected-frame-obj)))]))

                (empty? page-selected)
                (let [frame-id (ctst/top-nested-frame page-objects position)
                      delta    (gpt/subtract position orig-pos)]
                  [frame-id delta])

                :else
                (let [parent-id (:parent-id base)
                      delta     (if in-viewport?
                                  (gpt/subtract position orig-pos)
                                  (gpt/subtract (gpt/point (:selrect base)) orig-pos))]
                  [parent-id delta index]))))

          ;; Change the indexes of the pasted shapes
          (change-add-obj-index [objects selected index change]
            (let [;; if there is no current element selected, we want
                  ;; the first (inc index) to be 0
                  index (d/nilv index -1)
                  set-index (fn [[result index] id]
                              [(assoc result id index) (inc index)])

                  ;; FIXME: optimize ???
                  map-ids
                  (->> selected
                       (map #(get-in objects [% :id]))
                       (reduce set-index [{} (inc index)])
                       first)]

              (if (and (add-obj? change)
                       (contains? map-ids (:old-id change)))
                (assoc change :index (get map-ids (:old-id change)))
                change)))

          (process-shape [file-id frame-id parent-id shape]
            (cond-> shape
              :always
              (assoc :frame-id frame-id :parent-id parent-id)

              (and (or (cfh/group-shape? shape)
                       (cfh/bool-shape? shape))
                   (nil? (:shapes shape)))
              (assoc :shapes [])

              (cfh/text-shape? shape)
              (ctt/remove-external-typographies file-id)))]

    (ptk/reify ::paste-shapes
      ptk/WatchEvent
      (watch [it state _]
        (let [file-id      (:current-file-id state)
              page         (dsh/lookup-page state)

              media-idx    (->> (:images pdata)
                                (d/index-by :prev-id))

              selected     (:selected pdata)

              objects      (:objects pdata)

              variant-props (:variant-properties pdata)

              position     (deref ms/mouse-position)

              ;; Calculate position for the pasted elements
              [candidate-parent-id
               delta
               index]      (calculate-paste-position state objects selected position)

              page-objects (:objects page)

              libraries    (dsh/lookup-libraries state)
              ldata        (dsh/lookup-file-data state file-id)

              [parent-id
               frame-id]   (ctn/find-valid-parent-and-frame-ids candidate-parent-id page-objects (vals objects) true libraries)

              index        (if (= candidate-parent-id parent-id)
                             index
                             0)

              index        (if index
                             index
                             (dec (count (dm/get-in page-objects [parent-id :shapes]))))

              selected     (if (and (ctl/flex-layout? page-objects parent-id) (not (ctl/reverse? page-objects parent-id)))
                             (into (d/ordered-set) (reverse selected))
                             selected)

              objects      (update-vals objects (partial process-shape file-id frame-id parent-id))

              all-objects  (merge page-objects objects)

              drop-cell    (when (ctl/grid-layout? all-objects parent-id)
                             (gslg/get-drop-cell frame-id all-objects position))

              changes      (-> (pcb/empty-changes it)
                               (cll/generate-duplicate-changes all-objects page selected delta
                                                               libraries ldata file-id {:variant-props variant-props})
                               (pcb/amend-changes (partial process-rchange media-idx))
                               (pcb/amend-changes (partial change-add-obj-index objects selected index)))

              ;; Adds a resize-parents operation so the groups are
              ;; updated. We add all the new objects
              changes      (->> (:redo-changes changes)
                                (filter add-obj?)
                                (map :id)
                                (pcb/resize-parents changes))

              orig-shapes  (map (d/getf all-objects) selected)

              children-after (-> (pcb/get-objects changes)
                                 (dm/get-in [parent-id :shapes])
                                 set)

              ;; At the end of the process, we want to select the new created shapes
              ;; that are a direct child of the shape parent-id
              selected     (into (d/ordered-set)
                                 (comp
                                  (filter add-obj?)
                                  (map (comp :id :obj))
                                  (filter #(contains? children-after %)))
                                 (:redo-changes changes))

              changes      (cond-> changes
                             (some? drop-cell)
                             (pcb/update-shapes [parent-id]
                                                #(ctl/add-children-to-cell % selected all-objects drop-cell)))

              add-component-to-variant? (and
                                         ;; Any of the shapes is a head
                                         (some ctc/instance-head? orig-shapes)
                                         ;; Any ancestor of the destination parent is a variant
                                         (->> (cfh/get-parents-with-self page-objects parent-id)
                                              (some ctc/is-variant?)))
              undo-id      (js/Symbol)]

          (rx/concat
           (->> (rx/from orig-shapes)
                (rx/map (fn [shape]
                          (let [parent-type   (cfh/get-shape-type all-objects (:parent-id shape))
                                external-lib? (not= file-id (:component-file shape))
                                component     (ctn/get-component-from-shape shape libraries)
                                origin        "workspace:paste"]

                            ;; NOTE: we don't emit the create-shape event all the time for
                            ;; avoid send a lot of events (that are not necessary); this
                            ;; decision is made explicitly by the responsible team.
                            (if (ctc/instance-head? shape)
                              (ev/event {::ev/name "use-library-component"
                                         ::ev/origin origin
                                         :is-external-library external-lib?
                                         :type (get shape :type)
                                         :parent-type parent-type
                                         :is-variant (ctc/is-variant? component)})
                              (if (cfh/has-layout? objects (:parent-id shape))
                                (ev/event {::ev/name "layout-add-element"
                                           ::ev/origin origin
                                           :type (get shape :type)
                                           :parent-type parent-type})
                                (ev/event {::ev/name "create-shape"
                                           ::ev/origin origin
                                           :type (get shape :type)
                                           :parent-type parent-type})))))))

           (rx/of (dwu/start-undo-transaction undo-id)
                  (dch/commit-changes changes)
                  (dws/select-shapes selected)
                  (ptk/data-event :layout/update {:ids [frame-id]})
                  (dwu/commit-undo-transaction undo-id)
                  (when add-component-to-variant?
                    (ptk/event ::ev/event {::ev/name "add-component-to-variant"})))))))))

(defn- as-content [text]
  (let [paragraphs (->> (str/lines text)
                        (map str/trim)
                        (mapv #(hash-map :type "paragraph"
                                         :children [(merge (txt/get-default-text-attrs) {:text %})])))]
    ;; if text is composed only by line breaks paragraphs is an empty list and should be nil
    (when (d/not-empty? paragraphs)
      {:type "root"
       :children [{:type "paragraph-set" :children paragraphs}]})))

(defn- calculate-paste-position [state]
  (cond
    ;; Pasting inside a frame
    (selected-frame? state)
    (let [page-selected (dsh/lookup-selected state)
          page-objects  (dsh/lookup-page-objects state)
          frame-id (first page-selected)
          frame-object (get page-objects frame-id)]
      (gsh/shape->center frame-object))

    :else
    (deref ms/mouse-position)))

(defn- paste-html-text
  [html]
  (assert (string? html))
  (ptk/reify ::paste-html-text
    ptk/WatchEvent
    (watch [_ state  _]
      (let [style   (deref refs/workspace-clipboard-style)
            root    (dwtxt/create-root-from-html html style (features/active-feature? @st/state "text-editor/v2-html-paste"))
            text    (.-textContent root)
            content (tc/dom->cljs root)]
        (when (types.text/valid-content? content)
          (let [id     (uuid/next)
                width  (max 8 (min (* 7 (count text)) 700))
                height 16
                {:keys [x y]} (calculate-paste-position state)

                shape {:id id
                       :type :text
                       :name (txt/generate-shape-name text)
                       :x x
                       :y y
                       :width width
                       :height height
                       :grow-type (if (> (count text) 100) :auto-height :auto-width)
                       :content content}
                undo-id (js/Symbol)]
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dwsh/create-and-add-shape :text x y shape)
                   (dwu/commit-undo-transaction undo-id))))))))

(defn- paste-text
  [text]
  (dm/assert! (string? text))
  (ptk/reify ::paste-text
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (uuid/next)
            width (max 8 (min (* 7 (count text)) 700))
            height 16
            {:keys [x y]} (calculate-paste-position state)

            shape {:id id
                   :type :text
                   :name (txt/generate-shape-name text)
                   :x x
                   :y y
                   :width width
                   :height height
                   :grow-type (if (> (count text) 100) :auto-height :auto-width)
                   :content (as-content text)}
            undo-id (js/Symbol)]

        (rx/of (dwu/start-undo-transaction undo-id)
               (dwsh/create-and-add-shape :text x y shape)
               (dwu/commit-undo-transaction undo-id))))))

;; TODO: why not implement it in terms of upload-media-workspace?
(defn- paste-svg-text
  [text]
  (dm/assert! (string? text))
  (ptk/reify ::paste-svg-text
    ptk/WatchEvent
    (watch [_ state _]
      (let [position (calculate-paste-position state)
            file-id  (:current-file-id state)]
        (->> (dwm/svg->clj ["svg" text])
             (rx/map #(dwm/svg-uploaded % file-id position)))))))

(defn- paste-image
  [image]
  (ptk/reify ::paste-image
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id  (:current-file-id state)
            position (calculate-paste-position state)
            params   {:file-id file-id
                      :blobs [image]
                      :position position}]
        (rx/of (dwm/upload-media-workspace params))))))

(defn copy-link-to-clipboard
  []
  (ptk/reify ::copy-link-to-clipboard
    ptk/WatchEvent
    (watch [_ _ _]
      (clipboard/to-clipboard (rt/get-current-href)))))
