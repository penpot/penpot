(ns uxbox.ui.workspace.toolboxes
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as shapes]
            [uxbox.library :as library]
            [uxbox.util.data :refer (read-string)]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.util :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private ^:static drawing-shape
  "A focused vision of the drawing property
  of the workspace status. This avoids
  rerender the whole toolbox on each workspace
  change."
  (as-> (l/in [:workspace :drawing]) $
    (l/focus-atom $ st/state)))

(def ^:static ^:private shapes-by-id
  (as-> (l/key :shapes-by-id) $
    (l/focus-atom $ st/state)))

(defn- focus-page
  [pageid]
  (as-> (l/in [:pages-by-id pageid]) $
    (l/focus-atom $ st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Draw Tools
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:staric +draw-tools+
  {:rect
   {:icon i/box
    :help "Box (Ctrl + B)"
    :priority 10}
   :circle
   {:icon i/circle
    :help "Circle (Ctrl + E)"
    :priority 20}
   :line
   {:icon i/line
    :help "Line (Ctrl + L)"
    :priority 30}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Draw Tool Box
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn draw-tools-render
  [open-toolboxes]
  (let [workspace (rum/react wb/workspace-state)
        close #(rs/emit! (dw/toggle-toolbox :draw))
        tools (->> (into [] +draw-tools+)
                   (sort-by (comp :priority second)))]
    (html
     [:div#form-tools.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/window]
       [:span "Tools"]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       (for [[key props] tools]
         [:div.tool-btn.tooltip.tooltip-hover
          {:alt (:help props)
           :key (name key)
           :on-click (constantly nil)}
          (:icon props)])]])))

(def ^:static draw-tools
  (util/component
   {:render draw-tools-render
    :name "draw-tools"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Layers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- layer-element-render
  [own item selected]
  (println "layer-element-render" (:id item))
  (let [selected? (contains? selected (:id item))]
    (html
     [:li {:key (str (:id item))}
      [:div.element-actions
       [:div.toggle-element i/eye]
       [:div.block-element i/lock]]
      [:div.element-icon i/box]
      [:span (or (:name item)
                 (:id item))]])))

(def ^:static ^:private layer-element
  (util/component
   {:render layer-element-render
    :name "layer-element"
    :mixins [mx/static]}))

(defn layers-render
  [own]
  (let [workspace (rum/react wb/workspace-state)
        selected (:selected workspace)
        shapes-by-id (rum/react shapes-by-id)
        page (rum/react (focus-page (:page workspace)))
        close #(rs/emit! (dw/toggle-toolbox :layers))]
    (html
     [:div#layers.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/layers]
       [:span "Layers"]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       [:ul.element-list
        (for [shape (map #(get shapes-by-id %) (:shapes page))
              :let [component (layer-element shape selected)]]
          (rum/with-key component (:id shape)))]]
      [:div.layers-tools
       [:ul.layers-tools-content
        [:li.clone-layer i/copy]
        [:li.group-layer i/folder]
        [:li.delete-layer i/trash]
        ]]])))


        ;; [:li.group
        ;;  [:div.element-actions
        ;;   [:div.toggle-element i/eye]
        ;;   [:div.block-element i/lock]
        ;;   [:div.chain-element i/chain]]
        ;;  [:div.element-icon i/folder]
        ;;  [:span "Closed group"]
        ;;  [:span.toggle-content i/arrow-slide]]

        ;; [:li.group.open
        ;;  [:div.element-actions
        ;;   [:div.toggle-element i/eye]
        ;;   [:div.block-element i/lock]
        ;;   [:div.chain-element i/chain]]
        ;;  [:div.element-icon i/folder]
        ;;  [:span "Opened group"]
        ;;  [:span.toggle-content i/arrow-slide]]

        ;; [:li
        ;;  [:div.element-actions
        ;;   [:div.toggle-element i/eye]
        ;;   [:div.block-element i/lock]]
        ;;  [:div.sublevel-element i/sublevel]
        ;;  [:div.element-icon i/box]
        ;;  [:span "Sub layer"]]

        ;; [:li.group
        ;;  [:div.element-actions
        ;;   [:div.toggle-element i/eye]
        ;;   [:div.block-element i/lock]
        ;;   [:div.chain-element i/chain]]
        ;;  [:div.sublevel-element i/sublevel]
        ;;  [:div.element-icon i/folder]
        ;;  [:span "Sub group"]
        ;;  [:span.toggle-content i/arrow-slide]]]]


(def ^:static layers
  (util/component
   {:render layers-render
    :name "layers"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Icons
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- select-icon
  [icon]
  (if (= (:drawing @wb/workspace-state) icon)
    (rs/emit! (dw/select-for-drawing nil))
    (rs/emit! (dw/select-for-drawing icon))))

(defn- change-icon-coll
  [local event]
  (let [value (-> (dom/event->value event)
                  (read-string))]
    (swap! local assoc :collid value)
    (rs/emit! (dw/select-for-drawing nil))))

(defn- icon-wrapper-render
  [own icon]
  (shapes/render icon))

(def ^:static ^:private icon-wrapper
  (util/component
   {:render icon-wrapper-render
    :name "icon-wrapper"
    :mixins [mx/static]}))

(defn icons-render
  [own]
  (println "icons-render")
  (let [local (:rum/local own)
        drawing (rum/react drawing-shape)
        collid (:collid @local)
        icons (get-in library/+icon-collections-by-id+ [collid :icons])
        on-close #(rs/emit! (dw/toggle-toolbox :icons))
        on-select #(select-icon %)
        on-change #(change-icon-coll local %)]
    (html
     [:div#form-figures.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/window]
       [:span "Icons"]
       [:div.tool-window-close {:on-click on-close} i/close]]
      [:div.tool-window-content
       [:div.figures-catalog
        ;; extract component: set selector
        [:select.input-select.small {:on-change on-change
                                     :value collid}
         (for [icon-coll library/+icon-collections+]
           [:option {:key (str "icon-coll" (:id icon-coll))
                     :value (pr-str (:id icon-coll))}
            (:name icon-coll)])]]
       (for [icon icons
             :let [selected? (= drawing icon)]]
         [:div.figure-btn {:key (str (:id icon))
                           :class (when selected? "selected")
                           :on-click #(on-select icon)}
          (icon-wrapper icon)])]])))

(def ^:static icons
  (util/component
   {:render icons-render
    :name "icons-toolbox"
    :mixins [rum/reactive
             (mx/local {:collid 1 :builtin true})]}))
