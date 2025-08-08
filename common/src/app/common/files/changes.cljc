;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.changes
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.schema :as sm]
   [app.common.schema.desc-native :as smd]
   [app.common.schema.generators :as sg]
   [app.common.types.color :as ctc]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.grid :as ctg]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.typographies-list :as ctyl]
   [app.common.types.typography :as ctt]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [clojure.set :as set]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:operation
  [:multi {:dispatch :type
           :title "Operation"
           :decode/json #(update % :type keyword)
           ::smd/simplified true}
   [:assign
    [:map {:title "AssignOperation"}
     [:type [:= :assign]]
     ;; NOTE: the full decoding is happening on the handler because it
     ;; needs a proper context of the current shape and its type
     [:value [:map-of :keyword ::sm/any]]
     [:ignore-touched {:optional true} :boolean]
     [:ignore-geometry {:optional true} :boolean]]]
   [:set
    [:map {:title "SetOperation"}
     [:type [:= :set]]
     [:attr :keyword]
     [:val ::sm/any]
     [:ignore-touched {:optional true} :boolean]
     [:ignore-geometry {:optional true} :boolean]]]
   [:set-touched
    [:map {:title "SetTouchedOperation"}
     [:type [:= :set-touched]]
     [:touched [:maybe [:set :keyword]]]]]
   [:set-remote-synced
    [:map {:title "SetRemoteSyncedOperation"}
     [:type [:= :set-remote-synced]]
     [:remote-synced {:optional true} [:maybe :boolean]]]]])

(def schema:set-default-grid-change
  (let [gen (->> (sg/elements #{:square :column :row})
                 (sg/mcat (fn [grid-type]
                            (sg/fmap (fn [params]
                                       {:page-id (uuid/next)
                                        :type :set-default-grid
                                        :grid-type grid-type
                                        :params params})

                                     (case grid-type
                                       :square (sg/generator ctg/schema:square-params)
                                       :column (sg/generator ctg/schema:column-params)
                                       :row    (sg/generator ctg/schema:column-params))))))]

    [:multi {:decode/json #(update % :grid-type keyword)
             :gen/gen gen
             :dispatch :grid-type
             ::smd/simplified true}
     [:square
      [:map
       [:type [:= :set-default-grid]]
       [:page-id ::sm/uuid]
       [:grid-type [:= :square]]
       [:params [:maybe ctg/schema:square-params]]]]

     [:column
      [:map
       [:type [:= :set-default-grid]]
       [:page-id ::sm/uuid]
       [:grid-type [:= :column]]
       [:params [:maybe ctg/schema:column-params]]]]

     [:row
      [:map
       [:type [:= :set-default-grid]]
       [:page-id ::sm/uuid]
       [:grid-type [:= :row]]
       [:params [:maybe ctg/schema:column-params]]]]]))

(def schema:set-guide-change
  (let [schema [:map {:title "SetGuideChange"}
                [:type [:= :set-guide]]
                [:page-id ::sm/uuid]
                [:id ::sm/uuid]
                [:params [:maybe ::ctp/guide]]]
        gen    (->> (sg/generator schema)
                    (sg/fmap (fn [change]
                               (if (some? (:params change))
                                 (update change :params assoc :id (:id change))
                                 change))))]
    [:schema {:gen/gen gen} schema]))

(def schema:set-flow-change
  (let [schema [:map {:title "SetFlowChange"}
                [:type [:= :set-flow]]
                [:page-id ::sm/uuid]
                [:id ::sm/uuid]
                [:params [:maybe ::ctp/flow]]]

        gen    (->> (sg/generator schema)
                    (sg/fmap (fn [change]
                               (if (some? (:params change))
                                 (update change :params assoc :id (:id change))
                                 change))))]

    [:schema {:gen/gen gen} schema]))

(def schema:set-plugin-data-change
  (let [types  #{:file :page :shape :color :typography :component}

        schema [:map {:title "SetPagePluginData"}
                [:type [:= :set-plugin-data]]
                [:object-type [::sm/one-of types]]
                ;; It's optional because files don't need the id for type :file
                [:object-id {:optional true} ::sm/uuid]
                [:page-id {:optional true} ::sm/uuid]
                [:namespace {:gen/gen (sg/word-keyword)} :keyword]
                [:key {:gen/gen (sg/word-string)} :string]
                [:value [:maybe [:string {:gen/gen (sg/word-string)}]]]]

        check1 [:fn {:error/path [:page-id]
                     :error/message "missing page-id"}
                (fn [{:keys [object-type] :as change}]
                  (if (= :shape object-type)
                    (uuid? (:page-id change))
                    true))]

        gen    (->> (sg/generator schema)
                    (sg/filter :object-id)
                    (sg/filter :page-id)
                    (sg/fmap (fn [{:keys [object-type] :as change}]
                               (cond
                                 (= :file object-type)
                                 (-> change
                                     (dissoc :object-id)
                                     (dissoc :page-id))

                                 (= :shape object-type)
                                 change

                                 :else
                                 (dissoc change :page-id)))))]

    [:and {:gen/gen gen} schema check1]))

(def schema:change
  [:schema
   [:multi {:dispatch :type
            :title "Change"
            :decode/json #(update % :type keyword)
            ::smd/simplified true}
    [:set-option

     ;; DEPRECATED: remove before 2.3 release
     ;;
     ;; Is still there for not cause error when event is received
     [:map {:title "SetOptionChange"}]]

    [:set-comment-thread-position
     [:map
      [:comment-thread-id ::sm/uuid]
      [:page-id ::sm/uuid]
      [:frame-id [:maybe ::sm/uuid]]
      [:position [:maybe ::gpt/point]]]]

    [:add-obj
     [:map {:title "AddObjChange"}
      [:type [:= :add-obj]]
      [:id ::sm/uuid]
      [:obj :map]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]
      [:frame-id ::sm/uuid]
      [:parent-id {:optional true} [:maybe ::sm/uuid]]
      [:index {:optional true} [:maybe :int]]
      [:ignore-touched {:optional true} :boolean]]]

    [:mod-obj
     [:map {:title "ModObjChange"}
      [:type [:= :mod-obj]]
      [:id ::sm/uuid]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]
      [:operations [:vector {:gen/max 5} schema:operation]]]]

    [:del-obj
     [:map {:title "DelObjChange"}
      [:type [:= :del-obj]]
      [:id ::sm/uuid]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]
      [:ignore-touched {:optional true} :boolean]]]

    [:set-guide schema:set-guide-change]
    [:set-flow schema:set-flow-change]
    [:set-default-grid schema:set-default-grid-change]

    [:fix-obj
     [:map {:title "FixObjChange"}
      [:type [:= :fix-obj]]
      [:id ::sm/uuid]
      [:fix {:optional true} :keyword]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]]]

    [:mov-objects
     [:map {:title "MovObjectsChange"}
      [:type [:= :mov-objects]]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]
      [:ignore-touched {:optional true} :boolean]
      [:parent-id ::sm/uuid]
      [:shapes ::sm/any]
      [:index {:optional true} [:maybe :int]]
      [:after-shape {:optional true} ::sm/any]
      [:allow-altering-copies {:optional true} :boolean]]]

    [:reorder-children
     [:map {:title "ReorderChildrenChange"}
      [:type [:= :reorder-children]]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]
      [:ignore-touched {:optional true} :boolean]
      [:parent-id ::sm/uuid]
      [:shapes ::sm/any]]]

    [:add-page
     [:map {:title "AddPageChange"}
      [:type [:= :add-page]]
      [:id {:optional true} ::sm/uuid]
      [:name {:optional true} :string]
      [:page {:optional true} ::sm/any]]]

    [:mod-page
     [:map {:title "ModPageChange"}
      [:type [:= :mod-page]]
      [:id ::sm/uuid]
      ;; All props are optional, background can be nil because is the
      ;; way to remove already set background
      [:background {:optional true} [:maybe ctc/schema:hex-color]]
      [:name {:optional true} :string]]]

    [:set-plugin-data schema:set-plugin-data-change]

    [:del-page
     [:map {:title "DelPageChange"}
      [:type [:= :del-page]]
      [:id ::sm/uuid]]]

    [:mov-page
     [:map {:title "MovPageChange"}
      [:type [:= :mov-page]]
      [:id ::sm/uuid]
      [:index :int]]]

    [:reg-objects
     [:map {:title "RegObjectsChange"}
      [:type [:= :reg-objects]]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]
      [:shapes [:vector {:gen/max 5} ::sm/uuid]]]]

    [:add-color
     [:map {:title "AddColorChange"}
      [:type [:= :add-color]]
      [:color ctc/schema:library-color]]]

    [:mod-color
     [:map {:title "ModColorChange"}
      [:type [:= :mod-color]]
      [:color ctc/schema:library-color]]]

    [:del-color
     [:map {:title "DelColorChange"}
      [:type [:= :del-color]]
      [:id ::sm/uuid]]]

    ;; DEPRECATED: remove before 2.3
    [:add-recent-color
     [:map {:title "AddRecentColorChange"}]]

    [:add-media
     [:map {:title "AddMediaChange"}
      [:type [:= :add-media]]
      [:object ctf/schema:media]]]

    [:mod-media
     [:map {:title "ModMediaChange"}
      [:type [:= :mod-media]]
      [:object ctf/schema:media]]]

    [:del-media
     [:map {:title "DelMediaChange"}
      [:type [:= :del-media]]
      [:id ::sm/uuid]]]

    [:add-component
     [:map {:title "AddComponentChange"}
      [:type [:= :add-component]]
      [:id ::sm/uuid]
      [:name :string]
      [:shapes {:optional true} [:vector {:gen/max 3} ::sm/any]]
      [:path {:optional true} :string]]]

    [:mod-component
     [:map {:title "ModCompoenentChange"}
      [:type [:= :mod-component]]
      [:id ::sm/uuid]
      [:shapes {:optional true} [:vector {:gen/max 3} ::sm/any]]
      [:name {:optional true} :string]
      [:variant-id {:optional true} ::sm/uuid]
      [:variant-properties {:optional true} [:vector ::ctv/variant-property]]]]

    [:del-component
     [:map {:title "DelComponentChange"}
      [:type [:= :del-component]]
      [:id ::sm/uuid]
      ;; when it is an undo of a cut-paste, we need to undo the movement
      ;; of the shapes so we need to move them delta
      [:delta {:optional true} ::gpt/point]
      [:skip-undelete? {:optional true} :boolean]]]

    [:restore-component
     [:map {:title "RestoreComponentChange"}
      [:type [:= :restore-component]]
      [:id ::sm/uuid]
      [:page-id ::sm/uuid]]]

    [:purge-component
     [:map {:title "PurgeComponentChange"}
      [:type [:= :purge-component]]
      [:id ::sm/uuid]]]

    [:add-typography
     [:map {:title "AddTypogrphyChange"}
      [:type [:= :add-typography]]
      [:typography ::ctt/typography]]]

    [:mod-typography
     [:map {:title "ModTypogrphyChange"}
      [:type [:= :mod-typography]]
      [:typography ::ctt/typography]]]

    [:del-typography
     [:map {:title "DelTypogrphyChange"}
      [:type [:= :del-typography]]
      [:id ::sm/uuid]]]

    [:update-active-token-themes
     [:map {:title "UpdateActiveTokenThemes"}
      [:type [:= :update-active-token-themes]]
      [:theme-paths [:set :string]]]]

    [:rename-token-set-group
     [:map {:title "RenameTokenSetGroup"}
      [:type [:= :rename-token-set-group]]
      [:set-group-path [:vector :string]]
      [:set-group-fname :string]]]

    [:move-token-set
     [:map {:title "MoveTokenSet"}
      [:type [:= :move-token-set]]
      [:from-path [:vector :string]]
      [:to-path [:vector :string]]
      [:before-path [:maybe [:vector :string]]]
      [:before-group [:maybe :boolean]]]]

    [:move-token-set-group
     [:map {:title "MoveTokenSetGroup"}
      [:type [:= :move-token-set-group]]
      [:from-path [:vector :string]]
      [:to-path [:vector :string]]
      [:before-path [:maybe [:vector :string]]]
      [:before-group [:maybe :boolean]]]]

    [:set-token-theme
     [:map {:title "SetTokenThemeChange"}
      [:type [:= :set-token-theme]]
      [:theme-name :string]
      [:group :string]
      [:theme [:maybe ctob/schema:token-theme-attrs]]]]

    [:set-tokens-lib
     [:map {:title "SetTokensLib"}
      [:type [:= :set-tokens-lib]]
      [:tokens-lib ::sm/any]]]

    [:set-token-set
     [:map {:title "SetTokenSetChange"}
      [:type [:= :set-token-set]]
      [:set-name :string]
      [:group? :boolean]

      ;; FIXME: we should not pass private types as part of changes
      ;; protocol, the changes protocol should reflect a
      ;; method/protocol for perform surgical operations on file data,
      ;; this has nothing todo with internal types of a file data
      ;; structure.
      [:token-set {:gen/gen (sg/generator ctob/schema:token-set)}
       [:maybe [:fn ctob/token-set?]]]]]

    [:set-token
     [:map {:title "SetTokenChange"}
      [:type [:= :set-token]]
      [:set-name :string]
      [:token-name :string]
      [:token [:maybe ctob/schema:token-attrs]]]]

    [:set-base-font-size
     [:map {:title "ModBaseFontSize"}
      [:type [:= :set-base-font-size]]
      [:base-font-size :string]]]]])

(def schema:changes
  [:sequential {:gen/max 5 :gen/min 1} schema:change])

(sm/register! ::change schema:change)
(sm/register! ::changes schema:changes)

(def valid-change?
  (sm/lazy-validator schema:change))

(def check-changes!
  (sm/check-fn schema:changes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specific helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- without-obj
  "Clear collection from specified obj and without nil values."
  [coll o]
  (into [] (filter #(not= % o)) coll))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Transformation Changes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *touched-changes*
  "A dynamic var that used for track changes that touch shapes on
  first processing phase of changes. Should be set to a hash-set
  instance and will contain changes that caused the touched
  modification."
  nil)

(def ^:dynamic *state*
  "A general purpose state to signal some out of order operations
  to the processor backend."
  nil)

(defmulti process-change (fn [_ change] (:type change)))
(defmulti process-operation (fn [_ op] (:type op)))

;; Changes Processing Impl

(defn validate-shapes!
  [data-old data-new items]
  (letfn [(validate-shape! [[page-id id]]
            (let [shape-old (dm/get-in data-old [:pages-index page-id :objects id])
                  shape-new (dm/get-in data-new [:pages-index page-id :objects id])]

              ;; If object has changed or is new verify is correct
              (when (and (some? shape-new)
                         (not= shape-old shape-new))
                (when-not (and (cts/valid-shape? shape-new)
                               (cts/shape? shape-new))
                  (ex/raise :type :assertion
                            :code :data-validation
                            :hint (str "invalid shape found after applying changes on file "
                                       (:id data-new))
                            :file-id (:id data-new)
                            ::sm/explain (cts/explain-shape shape-new))))))]

    (->> (into #{} (map :page-id) items)
         (mapcat (fn [page-id]
                   (filter #(= page-id (:page-id %)) items)))
         (mapcat (fn [{:keys [type id page-id] :as item}]
                   (sequence
                    (map (partial vector page-id))
                    (case type
                      (:add-obj :mod-obj :del-obj) (cons id nil)
                      (:mov-objects :reg-objects)  (:shapes item)
                      nil))))
         (run! validate-shape!))))

(defn- process-touched-change
  [data {:keys [id page-id component-id]}]
  (let [objects (if page-id
                  (-> data :pages-index (get page-id) :objects)
                  (-> data :components (get component-id) :objects))
        shape   (get objects id)
        croot   (ctn/get-component-shape objects shape {:allow-main? true})]

    (if (and (some? croot) (ctk/main-instance? croot))
      (ctkl/set-component-modified data (:component-id croot))
      (if (some? component-id)
        (ctkl/set-component-modified data component-id)
        data))))

(defn process-changes
  ([data items]
   (process-changes data items true))

  ([data items verify?]
   ;; When verify? false we spec the schema validation. Currently used
   ;; to make just 1 validation even if the changes are applied twice
   (when verify?
     (check-changes! items))

   (binding [*touched-changes* (volatile! #{})
             cts/*wasm-sync* true]
     (let [result (reduce #(or (process-change %1 %2) %1) data items)
           result (reduce process-touched-change result @*touched-changes*)]
       ;; Validate result shapes (only on the backend)
       ;;
       ;; TODO: (PERF) add changed shapes tracking and only validate
       ;; the tracked changes instead of iterate over all shapes
       #?(:clj (validate-shapes! data result items))
       result))))

;; DEPRECATED: remove after 2.3 release
(defmethod process-change :set-option
  [data _]
  data)

;; --- Comment Threads

(defmethod process-change :set-comment-thread-position
  [data {:keys [page-id comment-thread-id position frame-id]}]
  (d/update-in-when data [:pages-index page-id]
                    (fn [page]
                      (if (and position frame-id)
                        (update page :comment-thread-positions assoc
                                comment-thread-id {:frame-id frame-id
                                                   :position position})
                        (update page :comment-thread-positions dissoc
                                comment-thread-id)))))

;; --- Guides

(defmethod process-change :set-guide
  [data {:keys [page-id id params]}]
  (if (nil? params)
    (d/update-in-when data [:pages-index page-id]
                      (fn [page]
                        (let [guides (get page :guides)
                              guides (dissoc guides id)]
                          (if (empty? guides)
                            (dissoc page :guides)
                            (assoc page :guides guides)))))

    (let [params (assoc params :id id)]
      (d/update-in-when data [:pages-index page-id] update :guides assoc id params))))

;; --- Flows

(defmethod process-change :set-flow
  [data {:keys [page-id id params]}]
  (if (nil? params)
    (d/update-in-when data [:pages-index page-id]
                      (fn [page]
                        (let [flows (get page :flows)
                              flows (dissoc flows id)]
                          (if (empty? flows)
                            (dissoc page :flows)
                            (assoc page :flows flows)))))

    (let [params (assoc params :id id)]
      (d/update-in-when data [:pages-index page-id] update :flows assoc id params))))

;; --- Grids

(defmethod process-change :set-default-grid
  [data {:keys [page-id grid-type params]}]
  (if (nil? params)
    (d/update-in-when data [:pages-index page-id]
                      (fn [page]
                        (let [default-grids (get page :default-grids)
                              default-grids (dissoc default-grids grid-type)]
                          (if (empty? default-grids)
                            (dissoc page :default-grids)
                            (assoc page :default-grids default-grids)))))
    (d/update-in-when data [:pages-index page-id] update :default-grids assoc grid-type params)))

;; --- Shape / Obj

;; The main purpose of this is ensure that all created shapes has
;; valid media references; so for make sure of it, we analyze each
;; shape added via `:add-obj` change for media usage, and if shape has
;; media refs, we put that media refs on the check list (on the
;; *state*) which will subsequently be processed and all incorrect
;; references will be corrected.  The media ref is anything that can
;; be pointing to a file-media-object on the shape, per example we
;; have fill-image, stroke-image, etc.

(defn- collect-shape-media-refs
  [state obj page-id]
  (let [media-refs
        (-> (cfh/collect-shape-media-refs obj)
            (not-empty))

        xform
        (map (fn [id]
               {:page-id page-id
                :shape-id (:id obj)
                :id id}))]

    (update state :media-refs into xform media-refs)))

(defmethod process-change :add-obj
  [data {:keys [id obj page-id component-id frame-id parent-id index ignore-touched]}]
  (let [update-container
        (fn [container]
          (ctst/add-shape id obj container frame-id parent-id index ignore-touched))]

    (when *state*
      (swap! *state* collect-shape-media-refs obj page-id))

    (if page-id
      (d/update-in-when data [:pages-index page-id] update-container)
      (d/update-in-when data [:components component-id] update-container))))

(defn- process-operations
  [objects {:keys [page-id id operations] :as change}]
  (if-let [shape (get objects id)]
    (let [shape    (reduce process-operation shape operations)
          touched? (-> shape meta ::ctn/touched)]
      ;; NOTE: processing operation functions can assign
      ;; the ::ctn/touched metadata on shapes, in this case we
      ;; need to report them for to be used in the second
      ;; phase of changes procesing
      (when touched? (some-> *touched-changes* (vswap! conj change)))

      (when (and *state* page-id)
        (swap! *state* collect-shape-media-refs shape page-id))

      (assoc objects id shape))

    objects))

(defmethod process-change :mod-obj
  [data {:keys [page-id component-id] :as change}]
  (if page-id
    (d/update-in-when data [:pages-index page-id :objects] process-operations change)
    (d/update-in-when data [:components component-id :objects] process-operations change)))

(defn- process-children-reordering
  [objects {:keys [parent-id shapes] :as change}]
  (if-let [old-shapes (dm/get-in objects [parent-id :shapes])]
    (let [id->idx
          (update-vals
           (->> (d/enumerate shapes)
                (group-by second))
           (comp first first))

          new-shapes
          (vec (sort-by #(d/nilv (id->idx %) -1) < old-shapes))]

      (if (not= old-shapes new-shapes)
        (do
          (some-> *touched-changes* (vswap! conj change))
          (update objects parent-id assoc :shapes new-shapes))
        objects))

    objects))

(defmethod process-change :reorder-children
  [data {:keys [page-id component-id] :as change}]
  (if page-id
    (d/update-in-when data [:pages-index page-id :objects] process-children-reordering change)
    (d/update-in-when data [:components component-id :objects] process-children-reordering change)))

(defmethod process-change :del-obj
  [data {:keys [page-id component-id id ignore-touched]}]
  (if page-id
    (d/update-in-when data [:pages-index page-id] ctst/delete-shape id ignore-touched)
    (d/update-in-when data [:components component-id] ctst/delete-shape id ignore-touched)))

(defmethod process-change :fix-obj
  [data {:keys [page-id component-id id] :as params}]
  (letfn [(fix-container [container]
            (case (:fix params :broken-children)
              :broken-children (ctst/fix-broken-children container id)
              (ex/raise :type :internal
                        :code :fix-not-implemented
                        :fix (:fix params))))]
    (if page-id
      (d/update-in-when data [:pages-index page-id] fix-container)
      (d/update-in-when data [:components component-id] fix-container))))

;; FIXME: remove, seems like this method is already unused
;; reg-objects operation "regenerates" the geometry and selrect of the parent groups
(defmethod process-change :reg-objects
  [data {:keys [page-id component-id shapes]}]
  ;; FIXME: Improve performance
  (letfn [(reg-objects [objects]
            (let [lookup    (d/getf objects)
                  update-fn #(d/update-when %1 %2 update-group %1)
                  xform     (comp
                             (mapcat #(cons % (cfh/get-parent-ids objects %)))
                             (filter #(contains? #{:group :bool} (-> % lookup :type)))
                             (distinct))]

              (->> (sequence xform shapes)
                   (reduce update-fn objects))))

          (set-mask-selrect [group children]
            (let [mask (first children)]
              (-> group
                  (assoc :selrect (-> mask :selrect))
                  (assoc :points  (-> mask :points))
                  (assoc :x       (-> mask :selrect :x))
                  (assoc :y       (-> mask :selrect :y))
                  (assoc :width   (-> mask :selrect :width))
                  (assoc :height  (-> mask :selrect :height))
                  (assoc :flip-x  (-> mask :flip-x))
                  (assoc :flip-y  (-> mask :flip-y)))))

          (update-group [group objects]
            (let [lookup   (d/getf objects)
                  children (get group :shapes)]
              (cond
                ;; If the group is empty we don't make any changes. Will be removed by a later process
                (empty? children)
                group

                (= :bool (:type group))
                (path/update-bool-shape group objects)

                (:masked-group group)
                (->> (map lookup children)
                     (set-mask-selrect group))

                :else
                (->> (map lookup children)
                     (gsh/update-group-selrect group)))))]

    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] reg-objects)
      (d/update-in-when data [:components component-id :objects] reg-objects))))

(defmethod process-change :mov-objects
  [data {:keys [parent-id shapes index page-id component-id ignore-touched after-shape allow-altering-copies syncing]}]
  (letfn [(calculate-invalid-targets [objects shape-id]
            (let [reduce-fn #(into %1 (calculate-invalid-targets objects %2))]
              (->> (get-in objects [shape-id :shapes])
                   (reduce reduce-fn #{shape-id}))))

          ;; Avoid placing a shape as a direct or indirect child of itself,
          ;; or inside its main component if it's in a copy,
          ;; or inside a copy, or from a copy
          (is-valid-move? [objects shape-id]
            (let [invalid-targets (calculate-invalid-targets objects shape-id)
                  shape (get objects shape-id)]
              (and shape
                   (not (invalid-targets parent-id))
                   (not (cfh/components-nesting-loop? objects shape-id parent-id))
                   (or allow-altering-copies ;; In some cases (like a component swap) it's allowed to change the structure of a copy
                       syncing ;; If we are syncing the changes of a main component, it's allowed to change the structure of a copy
                       (and
                        (not (ctk/in-component-copy? (get objects (:parent-id shape)))) ;; We don't want to change the structure of component copies
                        (not (ctk/in-component-copy? (get objects parent-id))))))))     ;; We need to check the origin and target frames

          (insert-items [prev-shapes index shapes]
            (let [prev-shapes (or prev-shapes [])]
              (if index
                (d/insert-at-index prev-shapes index shapes)
                (cfh/append-at-the-end prev-shapes shapes))))

          (add-to-parent [parent index shapes]
            (let [parent (-> parent
                             (update :shapes insert-items index shapes)
                             ;; We need to ensure that no `nil` in the
                             ;; shapes list after adding all the
                             ;; incoming shapes to the parent.
                             (update :shapes d/vec-without-nils))]
              (cond-> parent
                (and (:shape-ref parent)
                     (#{:group :frame} (:type parent))
                     (not ignore-touched))
                (dissoc :remote-synced))))

          (remove-from-old-parent [old-objects objects shape-id]
            (let [prev-parent-id (dm/get-in old-objects [shape-id :parent-id])]
              ;; Do nothing if the parent id of the shape is the same as
              ;; the new destination target parent id.
              (if (= prev-parent-id parent-id)
                objects
                (let [sid        shape-id
                      pid        prev-parent-id
                      obj        (get objects pid)
                      component? (and (:shape-ref obj)
                                      (= (:type obj) :group)
                                      (not ignore-touched))]
                  (-> objects
                      (d/update-in-when [pid :shapes] d/without-obj sid)
                      (d/update-in-when [pid :shapes] d/vec-without-nils)
                      (cond-> component? (d/update-when pid #(dissoc % :remote-synced))))))))

          (update-parent-id [objects id]
            (-> objects
                (d/update-when id assoc :parent-id parent-id)))

          ;; Updates the frame-id references that might be outdated
          (assign-frame-id [frame-id objects id]
            (let [objects (d/update-when objects id assoc :frame-id frame-id)
                  obj     (get objects id)]
              (cond-> objects
                ;; If we moving frame, the parent frame is the root
                ;; and we DO NOT NEED update children because the
                ;; children will point correctly to the frame what we
                ;; are currently moving
                (not= :frame (:type obj))
                (as-> $$ (reduce (partial assign-frame-id frame-id) $$ (:shapes obj))))))

          (move-objects [objects]
            (let [valid?   (every? (partial is-valid-move? objects) shapes)
                  parent   (get objects parent-id)
                  after-shape-index (d/index-of (:shapes parent) after-shape)
                  index (if (nil? after-shape-index) index (inc after-shape-index))
                  frame-id (if (= :frame (:type parent))
                             (:id parent)
                             (:frame-id parent))]

              (if (and valid? (seq shapes))
                (as-> objects $
                  ;; Add the new shapes to the parent object.
                  (d/update-when $ parent-id #(add-to-parent % index shapes))

                  ;; Update each individual shape link to the new parent
                  (reduce update-parent-id $ shapes)

                  ;; Analyze the old parents and clear the old links
                  ;; only if the new parent is different form old
                  ;; parent.
                  (reduce (partial remove-from-old-parent objects) $ shapes)

                  ;; Ensure that all shapes of the new parent has a
                  ;; correct link to the topside frame.
                  (reduce (partial assign-frame-id frame-id) $ shapes))
                objects)))]

    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] move-objects)
      (d/update-in-when data [:components component-id :objects] move-objects))))

(defmethod process-change :add-page
  [data {:keys [id name page]}]
  (when (and id name page)
    (ex/raise :type :conflict
              :hint "id+name or page should be provided, never both"))
  (let [page (if (and (string? name) (uuid? id))
               (ctp/make-empty-page {:id id :name name})
               page)]
    (ctpl/add-page data page)))

(defmethod process-change :mod-page
  [data {:keys [id] :as params}]
  (d/update-in-when data [:pages-index id]
                    (fn [page]
                      (let [name (get params :name)
                            bg   (get params :background :not-found)]
                        (cond-> page
                          (string? name)
                          (assoc :name name)

                          (string? bg)
                          (assoc :background bg)

                          (nil? bg)
                          (dissoc :background))))))

(defmethod process-change :set-plugin-data
  [data {:keys [object-type object-id page-id namespace key value]}]
  (letfn [(update-fn [data]
            (if (some? value)
              (assoc-in data [:plugin-data namespace key] value)
              (d/update-in-when data [:plugin-data namespace] dissoc key)))]

    (case object-type
      :file
      (update-fn data)

      :page
      (d/update-in-when data [:pages-index object-id] update-fn)

      :shape
      (d/update-in-when data [:pages-index page-id :objects object-id] update-fn)

      :color
      (d/update-in-when data [:colors object-id] update-fn)

      :typography
      (d/update-in-when data [:typographies object-id] update-fn)

      :component
      (d/update-in-when data [:components object-id] update-fn))))

(defmethod process-change :del-page
  [data {:keys [id]}]
  (ctpl/delete-page data id))

(defmethod process-change :mov-page
  [data {:keys [id index]}]
  (update data :pages d/insert-at-index index [id]))

(defmethod process-change :add-color
  [data {:keys [color]}]
  (ctc/add-color data color))

(defmethod process-change :mod-color
  [data {:keys [color]}]
  (ctc/set-color data color))

(defmethod process-change :del-color
  [data {:keys [id]}]
  (ctc/delete-color data id))

;; DEPRECATED: remove before 2.3
(defmethod process-change :add-recent-color
  [data _]
  data)


;; -- Media

(defmethod process-change :add-media
  [data {:keys [object]}]
  (update data :media assoc (:id object) object))

(defmethod process-change :mod-media
  [data {:keys [object]}]
  (d/update-in-when data [:media (:id object)] merge object))

(defmethod process-change :del-media
  [data {:keys [id]}]
  (d/update-when data :media dissoc id))

;; -- Components

(defmethod process-change :add-component
  [data params]
  (ctkl/add-component data params))

(defmethod process-change :mod-component
  [data params]
  (ctkl/mod-component data params))

(defmethod process-change :del-component
  [data {:keys [id skip-undelete? delta]}]
  (ctf/delete-component data id skip-undelete? delta))

(defmethod process-change :restore-component
  [data {:keys [id page-id]}]
  (ctf/restore-component data id page-id))

(defmethod process-change :purge-component
  [data {:keys [id]}]
  (ctf/purge-component data id))

;; -- Typography

(defmethod process-change :add-typography
  [data {:keys [typography]}]
  (ctyl/add-typography data typography))

(defmethod process-change :mod-typography
  [data {:keys [typography]}]
  (ctyl/update-typography data (:id typography) merge typography))

(defmethod process-change :del-typography
  [data {:keys [id]}]
  (ctyl/delete-typography data id))

;; -- Tokens

(defmethod process-change :set-tokens-lib
  [data {:keys [tokens-lib]}]
  (assoc data :tokens-lib tokens-lib))

(defmethod process-change :set-token
  [data {:keys [set-name token-name token]}]
  (update data :tokens-lib
          (fn [lib]
            (let [lib' (ctob/ensure-tokens-lib lib)]
              (cond
                (not token)
                (ctob/delete-token-from-set lib' set-name token-name)

                (not (ctob/get-token-in-set lib' set-name token-name))
                (ctob/add-token-in-set lib' set-name (ctob/make-token token))

                :else
                (ctob/update-token-in-set lib' set-name token-name (fn [prev-token]
                                                                     (ctob/make-token (merge prev-token token)))))))))

(defmethod process-change :set-token-set
  [data {:keys [set-name group? token-set]}]
  (update data :tokens-lib
          (fn [lib]
            (let [lib' (ctob/ensure-tokens-lib lib)]
              (cond
                (not token-set)
                (if group?
                  (ctob/delete-set-group lib' set-name)
                  (ctob/delete-set lib' set-name))

                (not (ctob/get-set lib' set-name))
                (ctob/add-set lib' token-set)

                :else
                (ctob/update-set lib' set-name (fn [_] token-set)))))))

(defmethod process-change :set-token-theme
  [data {:keys [group theme-name theme]}]
  (update data :tokens-lib
          (fn [lib]
            (let [lib' (ctob/ensure-tokens-lib lib)]
              (cond
                (not theme)
                (ctob/delete-theme lib' group theme-name)

                (not (ctob/get-theme lib' group theme-name))
                (ctob/add-theme lib' (ctob/make-token-theme theme))

                :else
                (ctob/update-theme lib'
                                   group theme-name
                                   (fn [prev-token-theme]
                                     (ctob/make-token-theme (merge prev-token-theme theme)))))))))

(defmethod process-change :update-active-token-themes
  [data {:keys [theme-paths]}]
  (update data :tokens-lib #(-> % (ctob/ensure-tokens-lib)
                                (ctob/set-active-themes theme-paths))))

(defmethod process-change :rename-token-set-group
  [data {:keys [set-group-path set-group-fname]}]
  (update data :tokens-lib (fn [lib]
                             (-> lib
                                 (ctob/ensure-tokens-lib)
                                 (ctob/rename-set-group set-group-path set-group-fname)))))

(defmethod process-change :move-token-set
  [data {:keys [from-path to-path before-path before-group] :as changes}]
  (update data :tokens-lib #(-> %
                                (ctob/ensure-tokens-lib)
                                (ctob/move-set from-path to-path before-path before-group))))

(defmethod process-change :move-token-set-group
  [data {:keys [from-path to-path before-path before-group]}]
  (update data :tokens-lib #(-> %
                                (ctob/ensure-tokens-lib)
                                (ctob/move-set-group from-path to-path before-path before-group))))

;; === Base font size

(defmethod process-change :set-base-font-size
  [data {:keys [base-font-size]}]
  (ctf/set-base-font-size data base-font-size))


;; === Operations

(def ^:private decode-shape
  (sm/decoder cts/schema:shape sm/json-transformer))

(defmethod process-operation :assign
  [{:keys [type] :as shape} {:keys [value] :as op}]
  (let [modifications (assoc value :type type)
        modifications (decode-shape modifications)]
    (reduce-kv (fn [shape k v]
                 (process-operation shape {:type :set
                                           :attr k
                                           :val v
                                           :ignore-touched (:ignore-touched op)
                                           :ignore-geometry (:ignore-geometry op)}))
               shape
               modifications)))

(defmethod process-operation :set
  [shape op]
  (ctn/set-shape-attr shape
                      (:attr op)
                      (:val op)
                      :ignore-touched (:ignore-touched op)
                      :ignore-geometry (:ignore-geometry op)))

(defmethod process-operation :set-touched
  [shape op]
  (let [touched  (:touched op)
        in-copy? (ctk/in-component-copy? shape)]
    (if (or (not in-copy?) (nil? touched) (empty? touched))
      (dissoc shape :touched)
      (assoc shape :touched touched))))

(defmethod process-operation :set-remote-synced
  [shape op]
  (let [remote-synced (:remote-synced op)
        in-copy?      (ctk/in-component-copy? shape)]
    (if (or (not in-copy?) (not remote-synced))
      (dissoc shape :remote-synced)
      (assoc shape :remote-synced true))))

(defmethod process-operation :default
  [_ op]
  (ex/raise :type :not-implemented
            :code :operation-not-implemented
            :context {:type (:type op)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component changes detection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Analyze one change and checks if if modifies the main instance of
;; any component, so that it needs to be synced immediately to the
;; main component. Return the ids of the components that need sync.

(defmulti components-changed (fn [_ change] (:type change)))

(defmethod components-changed :mod-obj
  [file-data {:keys [id page-id component-id operations]}]
  (let [need-sync? (fn [operation]
                     ; We need to trigger a sync if the shape has changed any
                     ; attribute that participates in components synchronization.
                     (and (= (:type operation) :set)
                          (ctk/component-attr? (:attr operation))))
        any-sync? (some need-sync? operations)]
    (when any-sync?
      (if page-id
        (let [page (ctpl/get-page file-data page-id)
              shape-and-parents (map #(ctn/get-shape page %)
                                     (cons id (cfh/get-parent-ids (:objects page) id)))
              xform (comp (filter :main-instance) ; Select shapes that are main component instances
                          (map :component-id))]
          (into #{} xform shape-and-parents))
        (when component-id
          #{component-id})))))

(defmethod components-changed :mov-objects
  [file-data {:keys [page-id _component-id parent-id shapes] :as change}]
  (when page-id
    (let [page  (ctpl/get-page file-data page-id)
          xform (comp (filter :main-instance)
                      (map :component-id))

          check-shape
          (fn [shape-id others]
            (let [all-parents (map (partial ctn/get-shape page)
                                   (concat others (cfh/get-parent-ids (:objects page) shape-id)))]
              (into #{} xform all-parents)))]

      (reduce #(set/union %1 (check-shape %2 []))
              (check-shape parent-id [parent-id])
              shapes))))

(defmethod components-changed :add-obj
  [file-data {:keys [parent-id page-id _component-id] :as change}]
  (when page-id
    (let [page (ctpl/get-page file-data page-id)
          parents (map (partial ctn/get-shape page)
                       (cons parent-id (cfh/get-parent-ids (:objects page) parent-id)))
          xform (comp (filter :main-instance)
                      (map :component-id))]
      (into #{} xform parents))))

(defmethod components-changed :del-obj
  [file-data {:keys [id page-id _component-id] :as change}]
  (when page-id
    (let [page (ctpl/get-page file-data page-id)
          shape-and-parents (map (partial ctn/get-shape page)
                                 (cons id (cfh/get-parent-ids (:objects page) id)))
          xform (comp (filter :main-instance)
                      (map :component-id))]
      (into #{} xform shape-and-parents))))

(defmethod components-changed :default
  [_ _]
  nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Copies changes detection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Analyze one change and checks if if modifies any shape belonging to
;; frames. Return the ids of the frames affected

(defn- parents-frames
  "Go trough the parents and get all of them that are a frame."
  [id objects]
  (->> (cfh/get-parents-with-self objects id)
       (filter cfh/frame-shape?)))

(defmulti frames-changed (fn [_ change] (:type change)))

(defmethod frames-changed :mod-obj
  [file-data {:keys [id page-id _component-id operations]}]
  (when page-id
    (let [page       (ctpl/get-page file-data page-id)
          need-sync? (fn [operation]
                       ; Check if the shape has changed any
                       ; attribute that participates in components synchronization.
                       (and (= (:type operation) :set)
                            (get ctk/sync-attrs (:attr operation))))
          any-sync? (some need-sync? operations)]
      (when any-sync?
        (parents-frames id (:objects page))))))

(defmethod frames-changed :mov-objects
  [file-data {:keys [page-id _component-id parent-id shapes] :as change}]
  (when page-id
    (let [page  (ctpl/get-page file-data page-id)]
      (concat
       (parents-frames parent-id (:objects page))
       (mapcat #(parents-frames (:parent-id %) (:objects page)) shapes)))))

(defmethod frames-changed :add-obj
  [file-data {:keys [parent-id page-id _component-id] :as change}]
  (when page-id
    (let [page (ctpl/get-page file-data page-id)]
      (parents-frames parent-id (:objects page)))))

(defmethod frames-changed :del-obj
  [file-data {:keys [id page-id _component-id] :as change}]
  (when page-id
    (let [page (ctpl/get-page file-data page-id)]
      (parents-frames id (:objects page)))))

(defmethod frames-changed :default
  [_ _]
  nil)
