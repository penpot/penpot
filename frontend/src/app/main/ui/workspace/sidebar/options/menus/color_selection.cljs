;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.color-selection
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.colors :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- prepare-colors
  "Prepares and groups extracted color information from shapes.
     Input:
       - shapes: vector of shape maps
       - file-id: current file UUID
       - libraries: shared color libraries

     Output:
       {:groups            explained below
        :all-colors        vector of all color maps (unique attrs)
        :colors            vector of normal colors (without ref-id or token)
        :library-colors    vector of colors linked to libraries (with ref-id)
        :token-colors      vector of colors linked to applied tokens
        :tokens            placeholder for future token data}

   :groups structure

   A map where:
     - Each **key** is a color descriptor map representing a unique color instance.
       Depending on the color type, it can contain:
         • :color  → hex string (e.g. \"#9f2929\")
         • :opacity → numeric value between 0-1
         • :ref-id and :ref-file → if the color comes from a library
         • :token-name \"some-token\" → if the color
           originates from an applied token

     - Each **value** is a vector of one or more maps describing *where* that
       color is used. Each entry corresponds to a specific shape and color
       property in the document:
         • :prop      → the property type (:fill, :stroke, :shadow, etc.)
         • :shape-id  → the UUID of the shape using this color
         • :index     → index of the color in the shape's fill/stroke list
   
   Example of groups:
   {
     {:color \"#9f2929\", :opacity 0.3,  :token-name \"asd2\" :has-token-applied true}
     [{:prop :fill, :shape-id #uuid \"d0231035-25c9-80d5-8006-eae4c3dff32e\", :index 0}]
   
     {:color \"#1b54b6\", :opacity 1}
     [{:prop :fill, :shape-id #uuid \"aab34f9a-98c1-801a-8006-eae5e8236f1b\", :index 0}]
   }
   
   This structure allows fast lookups of all shapes using the same visual color,
   regardless of whether it comes from local fills, strokes or shadow-colors."

  [shapes file-id libraries]
  (let [data           (into [] (remove nil?) (dwc/extract-all-colors shapes file-id libraries))
        groups         (d/group-by :attrs #(dissoc % :attrs) data)

         ;; Unique color attribute maps
        all-colors (distinct (mapv :attrs data))

        ;; Split into: library colors, token colors, and plain colors
        library-colors (filterv :ref-id all-colors)
        token-colors   (filterv :token-name all-colors)
        colors         (filterv #(and (nil? (:ref-id %))
                                      (not (:token-name %)))
                                all-colors)]
    {:groups groups
     :all-colors all-colors
     :colors colors
     :token-colors token-colors
     :library-colors library-colors}))

(def xf:map-shape-id
  (map :shape-id))

(defn- retrieve-color-operations
  [groups old-color prev-colors]
  (let [old-color               (-> old-color
                                    (dissoc :name :path)
                                    (d/without-nils))
        prev-color              (d/seek (partial get groups) prev-colors)
        color-operations-old    (get groups old-color)
        color-operations-prev   (get groups prev-colors)
        color-operations        (or color-operations-prev color-operations-old)
        old-color               (or prev-color old-color)]
    [color-operations old-color]))

(mf/defc color-selection-menu*
  {::mf/wrap [#(mf/memo' % (mf/check-props ["shapes"]))]}
  [{:keys [shapes file-id libraries]}]
  (let [{:keys [groups library-colors colors token-colors]}
        (mf/with-memo [file-id shapes libraries]
          (prepare-colors shapes file-id libraries))

        open*            (mf/use-state true)
        open?            (deref open*)

        has-colors?      (or (some? (seq colors)) (some? (seq library-colors)))

        toggle-content   (mf/use-fn #(swap! open* not))

        expand-lib-color (mf/use-state false)
        expand-color     (mf/use-state false)
        expand-token-color     (mf/use-state false)

        ;;  TODO: Review if this is still necessary.
        prev-colors-ref  (mf/use-ref nil)

        on-change
        (mf/use-fn
         (mf/deps groups)
         (fn [old-color new-color from-picker?]
           (let [prev-colors (mf/ref-val prev-colors-ref)
                 [color-operations old-color] (retrieve-color-operations groups old-color prev-colors)]

             ;;  TODO: Review if this is still necessary.
             (when from-picker?
               (let [color (-> new-color
                               (dissoc :name :path)
                               (d/without-nils))]
                 (mf/set-ref-val! prev-colors-ref
                                  (conj prev-colors color))))

             (st/emit! (dwc/change-color-in-selected color-operations new-color (dissoc old-color :token-name :has-token-applied))))))

        on-open
        (mf/use-fn #(mf/set-ref-val! prev-colors-ref []))

        on-close
        (mf/use-fn #(mf/set-ref-val! prev-colors-ref []))

        on-detach
        (mf/use-fn
         (mf/deps groups)
         (fn [color]
           (let [color-operations (get groups color)
                 color'           (dissoc color :ref-id :ref-file)]
             (st/emit! (dwc/change-color-in-selected color-operations color' color)))))

        on-detach-token
        (mf/use-fn
         (mf/deps token-colors groups)
         (fn [token]
           (let [prev-colors (mf/ref-val prev-colors-ref)
                 token-color (some #(when (= (:token-name %) (:name token)) %) token-colors)

                 [color-operations _] (retrieve-color-operations groups token-color prev-colors)]
             (doseq [op color-operations]
               (let [attr (if (= (:prop op) :stroke)
                            #{:stroke-color}
                            #{:fill})
                     color (-> token-color
                               (dissoc :token-name :has-token-applied)
                               (d/without-nils))]
                 (mf/set-ref-val! prev-colors-ref
                                  (conj prev-colors color))
                 (st/emit! (dwta/unapply-token {:attributes attr
                                                :token token
                                                :shape-ids [(:shape-id op)]})))))))

        select-only
        (mf/use-fn
         (mf/deps groups)
         (fn [color]
           (let [color-operations   (get groups color)
                 ids    (into (d/ordered-set) xf:map-shape-id color-operations)]
             (st/emit! (dws/select-shapes ids)))))

        on-token-change
        (mf/use-fn
         (mf/deps groups)
         (fn [_ token old-color]
           (let [prev-colors (mf/ref-val prev-colors-ref)
                 resolved-value (:resolved-value token)
                 new-color (dwta/value->color resolved-value)
                 color (-> new-color
                           (dissoc :name :path)
                           (d/without-nils))
                 [color-operations _] (retrieve-color-operations groups old-color prev-colors)]
             (mf/set-ref-val! prev-colors-ref
                              (conj prev-colors color))
             (st/emit! (dwta/apply-token-on-selected color-operations token)))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable  has-colors?
                      :collapsed    (not open?)
                      :on-collapsed toggle-content
                      :title        (tr "workspace.options.selection-color")
                      :class        (stl/css-case :title-spacing-selected-colors (not has-colors?))}]]

     (when open?
       [:div {:class (stl/css :element-content)}
        [:div {:class (stl/css :selected-color-group)}
         (let [library-colors-extract (cond->> library-colors (not @expand-lib-color) (take 3))]
           (for [[index color] (d/enumerate library-colors-extract)]
             [:> color-row*
              {:key index
               :color color
               :index index
               :on-detach #(on-detach color %)
               :select-only select-only
               :on-change #(on-change color %1  %2)
               :on-token-change #(on-token-change %1 %2 color)
               :on-open on-open
               :origin :color-selection
               :on-close on-close}]))
         (when (and (false? @expand-lib-color) (< 3 (count library-colors)))
           [:button  {:class (stl/css :more-colors-btn)
                      :on-click #(reset! expand-lib-color true)}
            (tr "workspace.options.more-lib-colors")])]

        [:div {:class (stl/css :selected-color-group)}
         (for [[index color] (d/enumerate (cond->> colors (not @expand-color) (take 3)))]
           [:> color-row*
            {:key index
             :color color
             :index index
             :select-only select-only
             :on-change #(on-change color %1 %2)
             :origin :color-selection
             :on-token-change #(on-token-change %1 %2 color)
             :on-open on-open
             :on-close on-close}])

         (when (and (false? @expand-color) (< 3 (count colors)))
           [:button  {:class (stl/css :more-colors-btn)
                      :on-click #(reset! expand-color true)}
            (tr "workspace.options.more-colors")])]

        [:div {:class (stl/css :selected-color-group)}
         (let [token-color-extract (cond->> token-colors (not @expand-token-color) (take 3))]
           (for [[index token-color] (d/enumerate token-color-extract)]
             (let [color {:color (:color token-color)
                          :opacity (:opacity token-color)}]
               [:> color-row*
                {:key index
                 :color color
                 :index index
                 :select-only select-only
                 :on-change #(on-change token-color %1 %2)
                 :origin :color-selection
                 :applied-token (:token-name token-color)
                 :on-detach-token on-detach-token
                 :on-token-change #(on-token-change %1 %2 token-color)
                 :on-open on-open
                 :on-close on-close}])))

         (when (and (false? @expand-token-color)
                    (< 3 (count token-colors)))
           [:button {:class (stl/css :more-colors-btn)
                     :on-click #(reset! expand-token-color true)}
            (tr "workspace.options.more-token-colors")])]])]))
