;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.color-selection
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.text :as txt]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.selection :as dws]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn fill->color-att
  [fill file-id shared-libs]
  (let [color-file-id      (:fill-color-ref-file fill)
        color-id           (:fill-color-ref-id fill)
        shared-libs-colors (dm/get-in shared-libs [color-file-id :data :colors])
        is-shared?         (contains? shared-libs-colors color-id)
        attrs              (if (or is-shared? (= color-file-id file-id))
                             (d/without-nils {:color    (str/lower (:fill-color fill))
                                              :opacity  (:fill-opacity fill)
                                              :id       color-id
                                              :file-id  color-file-id
                                              :gradient (:fill-color-gradient fill)})
                             (d/without-nils {:color    (str/lower (:fill-color fill))
                                              :opacity  (:fill-opacity fill)
                                              :gradient (:fill-color-gradient fill)}))]
    {:attrs attrs
     :prop :fill
     :shape-id (:shape-id fill)
     :index (:index fill)}))

(defn stroke->color-att
  [stroke file-id shared-libs]
  (let [color-file-id      (:stroke-color-ref-file stroke)
        color-id           (:stroke-color-ref-id stroke)
        shared-libs-colors (dm/get-in shared-libs [color-file-id :data :colors])
        is-shared?         (contains? shared-libs-colors color-id)
        has-color?         (not (nil? (:stroke-color stroke)))
        attrs              (if (or is-shared? (= color-file-id file-id))
                             (d/without-nils {:color    (str/lower (:stroke-color stroke))
                                              :opacity  (:stroke-opacity stroke)
                                              :id       color-id
                                              :file-id  color-file-id
                                              :gradient (:stroke-color-gradient stroke)})
                             (d/without-nils {:color    (str/lower (:stroke-color stroke))
                                              :opacity  (:stroke-opacity stroke)
                                              :gradient (:stroke-color-gradient stroke)}))]
    (when has-color?
      {:attrs attrs
       :prop :stroke
       :shape-id (:shape-id stroke)
       :index (:index stroke)})))

(defn shadow->color-att
  [shadow file-id shared-libs]
  (let [color-file-id      (dm/get-in shadow [:color :file-id])
        color-id           (dm/get-in shadow [:color :id])
        shared-libs-colors (dm/get-in shared-libs [color-file-id :data :colors])
        is-shared?         (contains? shared-libs-colors color-id)
        attrs              (if (or is-shared? (= color-file-id file-id))
                             (d/without-nils {:color    (str/lower (dm/get-in shadow [:color :color]))
                                              :opacity  (dm/get-in shadow [:color :opacity])
                                              :id       color-id
                                              :file-id  (dm/get-in shadow [:color :file-id])
                                              :gradient (dm/get-in shadow [:color :gradient])})
                             (d/without-nils {:color    (str/lower (dm/get-in shadow [:color :color]))
                                              :opacity  (dm/get-in shadow [:color :opacity])
                                              :gradient (dm/get-in shadow [:color :gradient])}))]
    

    {:attrs attrs
     :prop :shadow
     :shape-id (:shape-id shadow)
     :index (:index shadow)}))

(defn text->color-att
  [fill file-id shared-libs]
  (let [color-file-id      (:fill-color-ref-file fill)
        color-id           (:fill-color-ref-id fill)
        shared-libs-colors (dm/get-in shared-libs [color-file-id :data :colors])
        is-shared?         (contains? shared-libs-colors color-id)
        attrs              (if (or is-shared? (= color-file-id file-id))
                             (d/without-nils {:color    (str/lower (:fill-color fill))
                                              :opacity  (:fill-opacity fill)
                                              :id       color-id
                                              :file-id  color-file-id
                                              :gradient (:fill-color-gradient fill)})
                             (d/without-nils {:color    (str/lower (:fill-color fill))
                                              :opacity  (:fill-opacity fill)
                                              :gradient (:fill-color-gradient fill)}))]
    {:attrs attrs
     :prop :content
     :shape-id (:shape-id fill)
     :index (:index fill)}))

(defn treat-node
  [node shape-id]
  (map-indexed #(assoc %2 :shape-id shape-id :index %1) node))

(defn extract-text-colors
  [text file-id shared-libs]
  (let [content (txt/node-seq txt/is-text-node? (:content text))
        content-filtered (map :fills content)
        indexed (mapcat #(treat-node % (:id text)) content-filtered)]
    (map #(text->color-att % file-id shared-libs) indexed)))

(defn- extract-all-colors
  [shapes file-id shared-libs]
  (reduce
   (fn [list shape]
     (let [fill-obj   (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:fills shape))
           stroke-obj (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:strokes shape))
           shadow-obj (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:shadow shape))]
       (if (= :text (:type shape))
         (-> list
             (into (map #(stroke->color-att % file-id shared-libs)) stroke-obj)
             (into (map #(shadow->color-att % file-id shared-libs)) shadow-obj)
             (into (extract-text-colors shape file-id shared-libs)))

         (-> list
             (into (map #(fill->color-att % file-id shared-libs))  fill-obj)
             (into (map #(stroke->color-att % file-id shared-libs)) stroke-obj)
             (into (map #(shadow->color-att % file-id shared-libs)) shadow-obj)))))
   []
   shapes))

(defn- prepare-colors
  [shapes file-id shared-libs]
  (let [data           (into [] (remove nil? (extract-all-colors shapes file-id shared-libs)))
        grouped-colors (group-by :attrs data)
        all-colors     (distinct (mapv :attrs data))

        tmp            (group-by #(some? (:id %)) all-colors)
        library-colors (get tmp true)
        colors         (get tmp false)]

    {:grouped-colors grouped-colors
     :all-colors all-colors
     :colors colors
     :library-colors library-colors}))

(mf/defc color-selection-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["shapes"]))]}
  [{:keys [shapes file-id shared-libs] :as props}]
  (let [{:keys [all-colors grouped-colors library-colors colors]} (mf/with-memo [shapes file-id shared-libs]
                                                                    (prepare-colors shapes file-id shared-libs))
        expand-lib-color (mf/use-state false)
        expand-color     (mf/use-state false)

        grouped-colors*  (mf/use-var nil)
        prev-color*      (mf/use-var nil)

        on-change
        (mf/use-fn
         (fn [new-color old-color]
           (let [old-color       (-> old-color
                                     (dissoc :name)
                                     (dissoc :path)
                                     (d/without-nils))

                 prev-color       (-> @prev-color*
                                      (dissoc :name)
                                      (dissoc :path)
                                      (d/without-nils))

                 ;; When dragging on the color picker sometimes all the shapes hasn't updated the color to the prev value so we need this extra calculation
                 shapes-by-old-color  (get @grouped-colors* old-color)
                 shapes-by-prev-color (get @grouped-colors* prev-color)
                 shapes-by-color (or shapes-by-prev-color shapes-by-old-color)]
             (reset! prev-color* new-color)
             (st/emit! (dc/change-color-in-selected new-color shapes-by-color old-color)))))

        on-open (mf/use-fn
                 (fn [color]
                   (reset! prev-color* color)))

        on-detach
        (mf/use-fn
         (fn [color]
           (let [shapes-by-color (get @grouped-colors* color)
                 new-color       (assoc color :id nil :file-id nil)]
             (st/emit! (dc/change-color-in-selected new-color shapes-by-color color)))))

        select-only
        (mf/use-fn
         (fn [color]
           (let [shapes-by-color (get @grouped-colors* color)
                 ids (into (d/ordered-set) (map :shape-id) shapes-by-color)]
             (st/emit! (dws/select-shapes ids)))))]

    (mf/with-effect [grouped-colors]
      (reset! grouped-colors* grouped-colors))

    (when (> (count all-colors) 1)
      [:div.element-set
       [:div.element-set-title
        [:span (tr "workspace.options.selection-color")]]
       [:div.element-set-content
        [:div.selected-colors
         (for [[index color] (d/enumerate (take 3 library-colors))]
           [:& color-row {:key (dm/str "library-color-" index)
                          :color color
                          :index index
                          :on-detach on-detach
                          :select-only select-only
                          :on-change #(on-change % color)
                          :on-open on-open}])
         (when (and (false? @expand-lib-color) (< 3 (count library-colors)))
           [:div.expand-colors  {:on-click #(reset! expand-lib-color true)}
            [:span i/actions]
            [:span.text (tr "workspace.options.more-lib-colors")]])
         (when @expand-lib-color
           (for [[index color] (d/enumerate (drop 3 library-colors))]
             [:& color-row {:key (dm/str "library-color-" index)
                            :color color
                            :index index
                            :on-detach on-detach
                            :select-only select-only
                            :on-change #(on-change % color)
                            :on-open on-open}]))]

        [:div.selected-colors
         (for [[index color] (d/enumerate (take 3 colors))]
           [:& color-row {:key (dm/str "color-" index)
                          :color color
                          :index index
                          :select-only select-only
                          :on-change #(on-change % color)
                          :on-open on-open}])
         (when (and (false? @expand-color) (< 3 (count colors)))
           [:div.expand-colors  {:on-click #(reset! expand-color true)}
            [:span i/actions]
            [:span.text (tr "workspace.options.more-colors")]])
         (when @expand-color
           (for [[index color] (d/enumerate (drop 3 colors))]
             [:& color-row {:key (dm/str "color-" index)
                            :color color
                            :index index
                            :select-only select-only
                            :on-change #(on-change % color)
                            :on-open on-open}]))]]])))
