;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.shortcuts
  (:require
   [app.common.data :as d]
   [app.config :as cf]
   [app.main.data.dashboard.shortcuts]
   [app.main.data.shortcuts :as ds]
   [app.main.data.viewer.shortcuts]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path.shortcuts]
   [app.main.data.workspace.shortcuts]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.strings :refer [matches-search]]
   [clojure.string]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

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
        char          (if (contains? modified-keys (keyword key)) ((keyword char) modified-keys) char)
        char          (if (and is-macos? (contains? macos-keys (keyword char))) ((keyword char) macos-keys) char)
        unique-key    (str (d/name command) "-" char)]
    [:*
     [:span.char-box {:key unique-key} char]]))

(defn shortcuts->subsections
  "A function to obtain the list of subsections and their 
   associated shortcus from the general map of shortcuts"
  [shortcuts]
  (let [subsections (into #{} (mapcat :subsections) (vals shortcuts))
        get-sc-by-subsection
        (fn [subsection [k v]]
          (when (some #(= subsection %) (:subsections v)) k))
        reduce-sc
        (fn [acc subsection]
          (let [shortcuts-by-subsection (keep (partial get-sc-by-subsection subsection) shortcuts)]
            (assoc acc subsection shortcuts-by-subsection)))]
    (reduce reduce-sc {} subsections)))

(mf/defc shortcuts-keys
  [{:keys [content command] :as props}]
  (let [managed-list   (if (coll? content)
                         content
                         (conj () content))
        split-sc       (fn [sc]
                         (let [sc (cond-> sc (str/includes? sc "++")
                                          (str/replace "++" "+plus"))]
                           (if (= (count sc) 1)
                             [sc]
                             (str/split sc #"\+| "))))
        chars-list      (map split-sc managed-list)
        last-element    (last chars-list)
        short-char-list (if (= 1 (count chars-list))
                          chars-list
                          (drop-last chars-list))
        penultimate     (last short-char-list)]
    [:span.keys
     (for [chars short-char-list]
       [:*
        (for [char chars]
          [:& converted-chars {:char char :command command}])

        (when (not= chars penultimate) [:span.space ","])])
     (when (not= last-element penultimate)
       [:*
        [:span.space " or "]
        (for [char last-element]
          [:& converted-chars {:char char
                               :command command}])])]))

(mf/defc shortcut-row
  [{:keys [shortcuts elements filter-term match-section? match-subsection?] :as props}]
  (let [reduce-translation (fn [acc element] (assoc acc element (tr (str "shortcuts." (d/name element)))))
        translations       (reduce reduce-translation {} elements)
        filtered           (if (or match-section? match-subsection?)
                             (vals translations)
                             (filter #(matches-search % (:term @filter-term)) (vals translations)))
        sorted-filtered    (sort filtered)]
    [:ul.sub-menu
     (for [command-translate sorted-filtered]
       (let [command (first (filter (comp #{command-translate} translations) (keys translations)))
             content (:command (command shortcuts))
             name    (tr (str "shortcuts."  (d/name command)))]
         [:li.shortcut-name {:key command}
          [:span.command-name name]
          [:& shortcuts-keys {:content content
                              :command command}]]))]))

(mf/defc section-title
  [{:keys [is-visible? section elem-n] :as props}]
  (let [name (tr (str "shortcut-section."  (d/name section)))]
    [:div.section-title
     [:span.collapesed-shortcuts {:class (when is-visible? "open")} i/arrow-slide]
     [:span.section-name name]
     [:span.shortcut-count "(" elem-n ")"]]))


(mf/defc subsection-title
  [{:keys [subsection-name elements open-sections] :as props}]
  (let [subsection-name2 (tr (str "shortcut-subsection."  (d/name subsection-name)))]
    [:div.subsection-title
     [:span.collapesed-shortcuts {:class (when (some #(= % subsection-name)  (:subsection @open-sections)) "open")} i/arrow-slide]
     [:span.subsection-name subsection-name2]
     [:span.shortcut-count "(" elements ")"]]))

(mf/defc shortcut-subsection
  [{:keys [shortcuts-by-group manage-sections shortcuts filter-term match-section? open-sections] :as props}]
  (let [reduce-translation  (fn [acc subsection] (assoc acc subsection (tr (str "shortcut-subsection." (d/name subsection)))))
        translations        (reduce reduce-translation {} (keys shortcuts-by-group))
        sorted-translations (sort (vals translations))]
    [:ul.subsection-menu
     (for [sub-translate sorted-translations]
       (let [subsection (first (filter (comp #{sub-translate} translations) (keys translations)))
             elements (subsection shortcuts-by-group)
             visible? (some  #(= % subsection) (:subsection @open-sections))
             match-subsection? (matches-search sub-translate (:term @filter-term))

             keywords  (subsection shortcuts-by-group)
             matched-map (into {} (map (fn [element] {element (:translation (element shortcuts))}) keywords))
             translations (vals matched-map)
             match-shortcut-in-sub? (some #(matches-search % (:term @filter-term)) translations)
             shortcut-count (count (filter #(matches-search % (:term @filter-term)) translations))]
         (when (and (or match-section? match-subsection? match-shortcut-in-sub?) (not= subsection :basics))
           [:li {:key subsection
                 :on-click (manage-sections subsection true)}
            [:& subsection-title {:subsection-name subsection
                                  :open-sections open-sections
                                  :elements shortcut-count}]
            [:div {:style {:display (if visible? "initial" "none")}}
             [:& shortcut-row {:shortcuts shortcuts
                                        :elements elements
                                        :filter-term filter-term
                                        :match-section?  match-section?
                                        :match-subsection? match-subsection?}]]])))]))

(mf/defc shortcut-section
  [{:keys [section shortcuts shortcuts-by-group manage-sections filter-term open-sections] :as props}]
  (let [section-name        (d/name section)
        visible?            (some  #(= % section) (:section @open-sections))
        section-translation (tr (str "shortcut-section." section-name))
        match-section?      (matches-search section-translation (:term @filter-term))
        subsections         (map (fn [subsection] (tr (str "shortcut-subsection." (d/name subsection)))) (filter #(not= % :basics) (keys shortcuts-by-group)))
        match-subsection?   (some #(matches-search % (:term @filter-term)) subsections)
        keywords            (keys shortcuts)
        matched-map         (into {} (map (fn [element] {element (:translation (element shortcuts))}) keywords))
        translations        (vals matched-map)
        match-shortcut?     (some #(matches-search % (:term @filter-term)) translations)
        filtered-shortcuts  (count (filter #(matches-search % (:term @filter-term)) translations))]
    (when (or match-section? match-subsection? match-shortcut?)
      [:div  {:on-click (manage-sections section false)}
       [:& section-title {:is-visible? visible?
                          :section     section
                          :elem-n      filtered-shortcuts}]
       [:div {:style {:display (if visible? "initial" "none")}}
        [:& shortcut-subsection {:shortcuts-by-group shortcuts-by-group
                                  :manage-sections    manage-sections
                                  :match-section?     match-section?
                                  :open-sections      open-sections
                                  :filter-term        filter-term
                                  :shortcuts          shortcuts}]]])))

(defn add-translation-to-shorcuts
  [shortcuts]
  (map (fn [[k v]] [k (assoc v :translation (tr (str "shortcuts." (d/name k))))]) shortcuts))

(mf/defc shortcuts-container
  []
  (let [workspace-shortcuts          app.main.data.workspace.shortcuts/shortcuts
        path-shortcuts               app.main.data.workspace.path.shortcuts/shortcuts
        all-workspace-shortcuts      (->> (d/deep-merge path-shortcuts workspace-shortcuts)
                                          (add-translation-to-shorcuts)
                                          (into {}))

        dashboard-shortcuts          (->> app.main.data.dashboard.shortcuts/shortcuts
                                          (add-translation-to-shorcuts)
                                          (into {}))
        viewer-shortcuts             (->> app.main.data.viewer.shortcuts/shortcuts
                                          (add-translation-to-shorcuts)
                                          (into {}))

        all-shortcuts                (d/deep-merge all-workspace-shortcuts dashboard-shortcuts viewer-shortcuts)

        workspace-sc-by-subsections (shortcuts->subsections all-workspace-shortcuts)
        dashboard-sc-by-subsections (shortcuts->subsections dashboard-shortcuts)
        viewer-sc-by-subsections    (shortcuts->subsections viewer-shortcuts)

        ;; The basics section is treated separately because these elements 
        ;; are obtained from the rest of the listings of shortcuts.
        basics-elements              (concat (:basics workspace-sc-by-subsections)
                                             (:basics dashboard-sc-by-subsections)
                                             (:basics viewer-sc-by-subsections))
        reduce-translation           (fn [acc sc] (assoc acc sc (tr (str "shortcuts." (d/name sc)))))
        basics-translations          (reduce reduce-translation {} basics-elements)
        open-sections                (mf/use-state {:section [:workspace] :subsection []})
        basics-open?                 (some  #(= % :basics) (:section @open-sections))

        search-term                  (mf/use-state {:term ""})
        search-match-basics-items?   (some #(matches-search % (:term @search-term)) (vals basics-translations))
        search-match-basics?         (matches-search :basics (:term @search-term))

        close-fn                     #(st/emit! (dw/toggle-layout-flag :shortcuts))

        on-search-term-change
        (mf/use-callback
         (fn [event]
           (let [value (dom/get-target-val event)]
             (swap! search-term assoc :term value))))

        on-search-clear-click
        (mf/use-callback
         (fn [_]
           (swap! search-term assoc :term "")))

        manage-sections
        (fn [item is-sub?]
          (fn [event]
            (dom/stop-propagation event)
            (let [modify-atom
                  (fn [is-sub? item atom-name]
                    (let [keyword-name (if is-sub?
                                         :subsection
                                         :section)
                          is-present? (some #(= % item) (keyword-name atom-name))
                          value-vector (get atom-name keyword-name)
                          new-value (if is-present?
                                      (filterv (fn [element] (not= element item)) value-vector)
                                      (conj value-vector item))]
                      (assoc atom-name keyword-name new-value)))]

              (reset! open-sections (modify-atom is-sub? item @open-sections)))))]

    [:div.shortcuts
     [:div.shortcuts-header
      [:div.shortcuts-close-button
       {:on-click close-fn} i/close]
      [:div.shortcuts-title (tr "shortcuts.title")]]
     [:div.search-field
      [:div.search-box
       [:input.input-text
        {:id "shortcut-search"
         :placeholder (tr "shortcuts.search-placeholder")
         :type "text"
         :value (:term @search-term)
         :on-change on-search-term-change
         :auto-complete "off"}]
       (if (str/empty? (:term @search-term))
         [:span.icon-wrapper
          i/search]
         [:span.icon-wrapper.close
          {:on-click on-search-clear-click}
          i/close])]]

     [:div.shortcut-list
      (when (or search-match-basics-items? search-match-basics?)
        [:div {:on-click (manage-sections :basics false)}
         [:& section-title {:section     :basics
                            :is-visible? basics-open?
                            :elem-n      (count basics-elements)}]
         [:div {:style {:display (if basics-open? "initial" "none")}}
          [:& shortcut-row {:shortcuts      all-shortcuts
                                     :elements       basics-elements
                                     :filter-term    search-term
                                     :match-section? search-match-basics?}]]])

      [:& shortcut-section
       {:shortcuts-by-group workspace-sc-by-subsections
        :manage-sections    manage-sections
        :open-sections      open-sections
        :filter-term        search-term
        :shortcuts          all-workspace-shortcuts
        :section            :workspace}]

      [:& shortcut-section
       {:shortcuts-by-group dashboard-sc-by-subsections
        :manage-sections    manage-sections
        :open-sections      open-sections
        :filter-term        search-term
        :shortcuts          dashboard-shortcuts
        :section           :dashboard}]

      [:& shortcut-section
       {:shortcuts-by-group viewer-sc-by-subsections
        :manage-sections    manage-sections
        :open-sections      open-sections
        :filter-term        search-term
        :shortcuts          viewer-shortcuts
        :section           :viewer}]]]))
