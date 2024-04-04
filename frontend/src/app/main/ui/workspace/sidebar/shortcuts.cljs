;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.shortcuts
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.dashboard.shortcuts]
   [app.main.data.shortcuts :as ds]
   [app.main.data.viewer.shortcuts]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path.shortcuts]
   [app.main.data.workspace.shortcuts]
   [app.main.store :as st]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.strings :refer [matches-search]]
   [clojure.set :as set]
   [clojure.string]
   [rumext.v2 :as mf]))

(mf/defc converted-chars
  [{:keys [char command] :as props}]
  (let [modified-keys {:up    ds/up-arrow
                       :down  ds/down-arrow
                       :left  ds/left-arrow
                       :right ds/right-arrow
                       :plus "+"}
        macos-keys    {:command "\u2318"
                       :option  "\u2325"
                       :alt     "\u2325"
                       :delete  "\u232B"
                       :del     "\u232B"
                       :shift   "\u21E7"
                       :control "\u2303"
                       :esc     "\u238B"
                       :enter   "\u23CE"}
        is-macos?     (cf/check-platform? :macos)
        char          (if (contains? modified-keys (keyword char)) ((keyword char) modified-keys) char)
        char          (if (and is-macos? (contains? macos-keys (keyword char))) ((keyword char) macos-keys) char)
        unique-key    (str (d/name command) "-" char)]
    [:span {:class (stl/css :key)
            :key unique-key} char]))

(defn translation-keyname
  [type keyname]
  ;; Execution time translation strings:
  ;;   shortcut-subsection.alignment
  ;;   shortcut-subsection.edit
  ;;   shortcut-subsection.general-dashboard
  ;;   shortcut-subsection.general-viewer
  ;;   shortcut-subsection.main-menu
  ;;   shortcut-subsection.modify-layers
  ;;   shortcut-subsection.navigation-dashboard
  ;;   shortcut-subsection.navigation-viewer
  ;;   shortcut-subsection.navigation-workspace
  ;;   shortcut-subsection.panels
  ;;   shortcut-subsection.path-editor
  ;;   shortcut-subsection.shape
  ;;   shortcut-subsection.tools
  ;;   shortcut-subsection.zoom-viewer
  ;;   shortcut-subsection.zoom-workspace
  ;;   shortcuts.add-comment
  ;;   shortcuts.add-node
  ;;   shortcuts.align-bottom
  ;;   shortcuts.align-hcenter
  ;;   shortcuts.align-left
  ;;   shortcuts.align-right
  ;;   shortcuts.align-top
  ;;   shortcuts.align-vcenter
  ;;   shortcuts.artboard-selection
  ;;   shortcuts.bool-difference
  ;;   shortcuts.bool-exclude
  ;;   shortcuts.bool-intersection
  ;;   shortcuts.bool-union
  ;;   shortcuts.bring-back
  ;;   shortcuts.bring-backward
  ;;   shortcuts.bring-forward
  ;;   shortcuts.bring-front
  ;;   shortcuts.clear-undo
  ;;   shortcuts.copy
  ;;   shortcuts.create-component
  ;;   shortcuts.create-new-project
  ;;   shortcuts.cut
  ;;   shortcuts.decrease-zoom
  ;;   shortcuts.delete
  ;;   shortcuts.delete-node
  ;;   shortcuts.detach-component
  ;;   shortcuts.draw-curve
  ;;   shortcuts.draw-ellipse
  ;;   shortcuts.draw-frame
  ;;   shortcuts.draw-nodes
  ;;   shortcuts.draw-path
  ;;   shortcuts.draw-rect
  ;;   shortcuts.draw-text
  ;;   shortcuts.duplicate
  ;;   shortcuts.escape
  ;;   shortcuts.export-shapes
  ;;   shortcuts.fit-all
  ;;   shortcuts.flip-horizontal
  ;;   shortcuts.flip-vertical
  ;;   shortcuts.go-to-drafts
  ;;   shortcuts.go-to-libs
  ;;   shortcuts.go-to-search
  ;;   shortcuts.group
  ;;   shortcuts.h-distribute
  ;;   shortcuts.hide-ui
  ;;   shortcuts.increase-zoom
  ;;   shortcuts.insert-image
  ;;   shortcuts.join-nodes
  ;;   shortcuts.make-corner
  ;;   shortcuts.make-curve
  ;;   shortcuts.mask
  ;;   shortcuts.merge-nodes
  ;;   shortcuts.move
  ;;   shortcuts.move-fast-down
  ;;   shortcuts.move-fast-left
  ;;   shortcuts.move-fast-right
  ;;   shortcuts.move-fast-up
  ;;   shortcuts.move-nodes
  ;;   shortcuts.move-unit-down
  ;;   shortcuts.move-unit-left
  ;;   shortcuts.move-unit-right
  ;;   shortcuts.move-unit-up
  ;;   shortcuts.next-frame
  ;;   shortcuts.opacity-0
  ;;   shortcuts.opacity-1
  ;;   shortcuts.opacity-2
  ;;   shortcuts.opacity-3
  ;;   shortcuts.opacity-4
  ;;   shortcuts.opacity-5
  ;;   shortcuts.opacity-6
  ;;   shortcuts.opacity-7
  ;;   shortcuts.opacity-8
  ;;   shortcuts.opacity-9
  ;;   shortcuts.open-color-picker
  ;;   shortcuts.open-comments
  ;;   shortcuts.open-dashboard
  ;;   shortcuts.select-prev
  ;;   shortcuts.select-next
  ;;   shortcuts.open-inspect
  ;;   shortcuts.open-interactions
  ;;   shortcuts.open-viewer
  ;;   shortcuts.open-workspace
  ;;   shortcuts.paste
  ;;   shortcuts.prev-frame
  ;;   shortcuts.redo
  ;;   shortcuts.reset-zoom
  ;;   shortcuts.select-all
  ;;   shortcuts.separate-nodes
  ;;   shortcuts.show-pixel-grid
  ;;   shortcuts.show-shortcuts
  ;;   shortcuts.snap-nodes
  ;;   shortcuts.snap-pixel-grid
  ;;   shortcuts.start-editing
  ;;   shortcuts.start-measure
  ;;   shortcuts.stop-measure
  ;;   shortcuts.text-align-center
  ;;   shortcuts.text-align-left
  ;;   shortcuts.text-align-justify
  ;;   shortcuts.text-align-right
  ;;   shortcuts.thumbnail-set
  ;;   shortcuts.toggle-alignment
  ;;   shortcuts.toggle-assets
  ;;   shortcuts.toggle-colorpalette
  ;;   shortcuts.toggle-focus-mode
  ;;   shortcuts.toggle-guides
  ;;   shortcuts.toggle-history
  ;;   shortcuts.toggle-layers
  ;;   shortcuts.toggle-lock
  ;;   shortcuts.toggle-lock-size
  ;;   shortcuts.toggle-rules
  ;;   shortcuts.scale
  ;;   shortcuts.toggle-snap-guides
  ;;   shortcuts.toggle-snap-ruler-guide
  ;;   shortcuts.toggle-textpalette
  ;;   shortcuts.toggle-visibility
  ;;   shortcuts.toggle-zoom-style
  ;;   shortcuts.toggle-fullscreen
  ;;   shortcuts.undo
  ;;   shortcuts.ungroup
  ;;   shortcuts.unmask
  ;;   shortcuts.v-distribute
  ;;   shortcuts.zoom-selected
  ;;   shortcuts.toggle-layout-grid
  (let [translat-pre (case type
                       :sc      "shortcuts."
                       :sec     "shortcut-section."
                       :sub-sec "shortcut-subsection.")]
    (tr (str translat-pre (d/name keyname)))))

(defn add-translation
  [type item]
  (map (fn [[k v]] [k (assoc v :translation (translation-keyname type k))]) item))

(defn shortcuts->subsections
  "A function to obtain the list of subsections and their
   associated shortcus from the general map of shortcuts"
  [shortcuts]
  (let [subsections (into #{} (mapcat :subsections) (vals shortcuts))
        get-sc-by-subsection
        (fn [subsection [k v]]
          (when (some #(= subsection %) (:subsections v)) {k v}))
        reduce-sc
        (fn [acc subsection]
          (let [shortcuts-by-subsection (into {} (keep (partial get-sc-by-subsection subsection) shortcuts))]
            (assoc acc subsection {:children shortcuts-by-subsection})))]
    (reduce reduce-sc {} subsections)))

(mf/defc shortcuts-keys
  [{:keys [content command] :as props}]
  (let [managed-list    (if (coll? content)
                          content
                          (conj () content))
        chars-list      (map ds/split-sc managed-list)
        last-element    (last chars-list)
        short-char-list (if (= 1 (count chars-list))
                          chars-list
                          (drop-last chars-list))
        penultimate     (last short-char-list)]
    [:span {:class (stl/css :keys)}
     (for [chars short-char-list]
       [:*
        (for [char chars]
          [:& converted-chars {:key (dm/str char "-" (name command))
                               :char char
                               :command command}])
        (when (not= chars penultimate) [:span {:class (stl/css :space)} ","])])
     (when (not= last-element penultimate)
       [:*
        [:span {:class (stl/css :space)} (tr "shortcuts.or")]
        (for [char last-element]
          [:& converted-chars {:key (dm/str char "-" (name command))
                               :char char
                               :command command}])])]))

(mf/defc shortcut-row
  [{:keys [elements filter-term match-section? match-subsection?] :as props}]
  (let [shortcut-name         (keys elements)
        shortcut-translations (map #(translation-keyname :sc %) shortcut-name)
        match-shortcut?       (some #(matches-search % @filter-term) shortcut-translations)
        filtered              (if (and (or match-section? match-subsection?) (not match-shortcut?))
                                shortcut-translations
                                (filter #(matches-search % @filter-term) shortcut-translations))
        sorted-filtered       (sort filtered)]

    [:ul {:class (stl/css :sub-menu)}
     (for [command-translate sorted-filtered]
       (let [sc-by-translate  (first (filter #(= (:translation (second %)) command-translate) elements))
             [command  comand-info] sc-by-translate
             content                (or (:show-command comand-info) (:command comand-info))]
         [:li {:class (stl/css :shortcuts-name)
               :key command-translate}
          [:span {:class (stl/css :command-name)}
           command-translate]
          [:& shortcuts-keys {:content content
                              :command command}]]))]))

(mf/defc section-title
  [{:keys [is-visible? name is-sub?] :as props}]
  [:div {:class (if is-sub?
                  (stl/css :subsection-title)
                  (stl/css :section-title))}
   [:span {:class (stl/css-case :open is-visible?
                                :collapsed-shortcuts true)} i/arrow]
   [:span {:class (if is-sub?
                    (stl/css :subsection-name)
                    (stl/css :section-name))} name]])

(mf/defc shortcut-subsection
  [{:keys [subsections manage-sections filter-term match-section? open-sections] :as props}]
  (let [subsections-names       (keys subsections)
        subsection-translations (if (= :none (first subsections-names))
                                  (map #(translation-keyname :sc %) subsections-names)
                                  (map #(translation-keyname :sub-sec %) subsections-names))
        sorted-translations     (sort subsection-translations)]
    ;; Basics section is treated different because it has no sub sections
    (if (= :none (first subsections-names))
      (let [basic-shortcuts (:none subsections)]
        [:& shortcut-row {:elements (:children basic-shortcuts)
                          :filter-term filter-term
                          :match-section? match-section?
                          :match-subsection? true}])

      [:ul {:class (stl/css :subsection-menu)}
       (for [sub-translated sorted-translations]
         (let [sub-by-translate    (first (filter #(= (:translation (second %)) sub-translated) subsections))
               [sub-name sub-info] sub-by-translate
               visible?            (some  #(= % (:id sub-info)) @open-sections)
               match-subsection?   (matches-search (translation-keyname :sub-sec sub-name) @filter-term)
               shortcut-names      (map #(translation-keyname :sc %) (keys (:children sub-info)))
               match-shortcuts?    (some #(matches-search % @filter-term) shortcut-names)]
           (when (or match-subsection? match-shortcuts? match-section?)
             [:li {:key sub-translated
                   :on-click (manage-sections (:id sub-info))}
              [:& section-title {:name sub-translated
                                 :is-sub? true
                                 :is-visible? visible?}]

              [:div {:style {:display (if visible? "initial" "none")}}
               [:& shortcut-row {:elements (:children sub-info)
                                 :filter-term filter-term
                                 :match-section?  match-section?
                                 :match-subsection? match-subsection?}]]])))])))

(mf/defc shortcut-section
  [{:keys [section manage-sections open-sections filter-term] :as props}]
  (let [[section-key section-info] section
        section-id          (:id section-info)
        section-translation (translation-keyname :sec section-key)
        match-section?      (matches-search section-translation @filter-term)
        subsections         (:children section-info)
        subs-names          (keys subsections)
        subs-bodys          (reduce #(conj %1 (:children (%2 subsections))) {} subs-names)
        sub-trans           (map #(if (= "none" (d/name %))
                                    nil
                                    (translation-keyname :sub-sec %)) subs-names)
        match-subsection?   (some #(matches-search % @filter-term) sub-trans)
        translations        (map #(translation-keyname :sc %) (keys subs-bodys))
        match-shortcut?     (some #(matches-search % @filter-term) translations)
        visible?            (some  #(= % section-id) @open-sections)]

    (when (or match-section? match-subsection? match-shortcut?)
      [:div {:class (stl/css :section)
             :on-click (manage-sections section-id)}
       [:& section-title {:is-visible? visible?
                          :is-sub? false
                          :name    section-translation}]

       [:div {:style {:display (if visible? "initial" "none")}}
        [:& shortcut-subsection {:subsections     subsections
                                 :open-sections   open-sections
                                 :manage-sections manage-sections
                                 :match-section?  match-section?
                                 :filter-term     filter-term}]]])))

(mf/defc shortcuts-container
  [{:keys [class] :as props}]
  (let [workspace-shortcuts          app.main.data.workspace.shortcuts/shortcuts
        path-shortcuts               app.main.data.workspace.path.shortcuts/shortcuts
        all-workspace-shortcuts      (->> (d/deep-merge path-shortcuts workspace-shortcuts)
                                          (add-translation :sc)
                                          (into {}))

        dashboard-shortcuts          (->> app.main.data.dashboard.shortcuts/shortcuts
                                          (add-translation :sc)
                                          (into {}))
        viewer-shortcuts             (->> app.main.data.viewer.shortcuts/shortcuts
                                          (add-translation :sc)
                                          (into {}))
        open-sections                (mf/use-state [[1]])
        filter-term                  (mf/use-state "")

        close-fn                     #(st/emit! (dw/toggle-layout-flag :shortcuts))

        walk (fn walk [element parent-id]
               (if (nil? element)
                 element
                 (let [rec-fn (fn [index [k item]]
                                (let [item-id (if (nil? parent-id)
                                                [index]
                                                (conj parent-id index))]
                                  [k (assoc item :id item-id :children  (walk (:children item) item-id))]))]
                   (into {} (map-indexed (partial rec-fn) element)))))

        workspace-sc-by-subsections (->> (shortcuts->subsections all-workspace-shortcuts)
                                         (add-translation :sub-sec)
                                         (into {}))
        dashboard-sc-by-subsections (->> (shortcuts->subsections dashboard-shortcuts)
                                         (add-translation :sub-sec)
                                         (into {}))
        viewer-sc-by-subsections    (->> (shortcuts->subsections viewer-shortcuts)
                                         (add-translation :sub-sec)
                                         (into {}))
        basics-elements             (into {} (concat (:children (:basics workspace-sc-by-subsections))
                                                     (:children (:basics dashboard-sc-by-subsections))
                                                     (:children (:basics viewer-sc-by-subsections))))

        workspace-sc-by-subsections (dissoc workspace-sc-by-subsections :basics)
        dashboard-sc-by-subsections (dissoc dashboard-sc-by-subsections :basics)
        viewer-sc-by-subsections    (dissoc viewer-sc-by-subsections :bassics)

        all-shortcuts               {:basics    {:id [1]
                                                 :children {:none {:children basics-elements}}
                                                 :translation (tr "shortcut-section.basics")}
                                     :workspace {:id [2]
                                                 :children workspace-sc-by-subsections
                                                 :translation (tr "shortcut-section.workspace")}
                                     :dashboard {:id [3]
                                                 :children dashboard-sc-by-subsections
                                                 :translation (tr "shortcut-section.dashboard")}
                                     :viewer    {:id [4]
                                                 :children viewer-sc-by-subsections
                                                 :translation (tr "shortcut-section.viewer")}}
        all-shortcuts               (walk all-shortcuts nil)

        all-sc-names                (map #(translation-keyname :sc %) (concat
                                                                       (keys all-workspace-shortcuts)
                                                                       (keys dashboard-shortcuts)
                                                                       (keys viewer-shortcuts)))

        all-sub-names               (map #(translation-keyname :sub-sec %) (concat
                                                                            (keys workspace-sc-by-subsections)
                                                                            (keys dashboard-sc-by-subsections)
                                                                            (keys viewer-sc-by-subsections)))
        all-section-names           (map #(translation-keyname :sec %) (keys all-shortcuts))
        all-item-names              (concat all-sc-names all-sub-names all-section-names)
        match-any?                  (some #(matches-search % @filter-term) all-item-names)

        manage-sections
        (fn [item]
          (fn [event]
            (dom/stop-propagation event)
            (let [is-present? (some #(= % item) @open-sections)
                  new-value (if is-present?
                              (filterv (fn [element] (not= element item)) @open-sections)
                              (conj @open-sections item))]
              (reset! open-sections new-value))))

        add-ids (fn [acc node]
                  (let [id (:id node)
                        addition (case (count id)
                                   1 id
                                   2 [[(first id)] id]
                                   3 [[(first id)] [(first id) (second id)]]
                                   "default" nil)]
                    (if (= 1 (count addition))
                      (conj acc addition)
                      (into [] (concat acc addition)))))

        manage-section-on-search
        (fn [section term]
          (let [node-seq (tree-seq :children #(vals (:children %)) (get all-shortcuts section))]
            (reduce (fn [acc node]
                      (if (matches-search (:translation node) term)
                        (add-ids acc node)
                        acc))
                    []
                    node-seq)))

        manage-sections-on-search
        (fn [term]
          (if (= term "")
            (reset! open-sections [[1]])
            (let [ids (set/union (manage-section-on-search :basics term)
                                 (manage-section-on-search :workspace term)
                                 (manage-section-on-search :dashboard term)
                                 (manage-section-on-search :viewer term))]
              (reset! open-sections ids))))

        on-search-term-change-2
        (mf/use-callback
         (fn [value]
           (manage-sections-on-search value)
           (reset! filter-term value)))
        on-search-clear-click
        (mf/use-callback
         (fn [_]
           (reset! open-sections [[1]])
           (reset! filter-term "")))]

    (mf/with-effect []
      (dom/focus! (dom/get-element "shortcut-search")))

    [:div {:class (dm/str class " " (stl/css :shortcuts))}
     [:div {:class (stl/css :shortcuts-header)}
      [:div {:class (stl/css :shortcuts-title)} (tr "shortcuts.title")]
      [:div {:class (stl/css :shortcuts-close-button)
             :on-click close-fn}
       i/close]]
     [:div {:class (stl/css :search-field)}

      [:& search-bar {:on-change on-search-term-change-2
                      :clear-action on-search-clear-click
                      :value @filter-term
                      :placeholder (tr "shortcuts.title")
                      :icon (mf/html [:span {:class (stl/css :search-icon)} i/search])}]]

     (if match-any?
       [:div {:class (stl/css :shortcuts-list)}
        (for [section all-shortcuts]
          [:& shortcut-section
           {:section section
            :manage-sections manage-sections
            :open-sections open-sections
            :filter-term filter-term}])]
       [:div {:class (stl/css :not-found)} (tr "shortcuts.not-found")])]))
