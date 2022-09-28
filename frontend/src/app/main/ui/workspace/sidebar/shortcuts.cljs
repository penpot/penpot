;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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
   [clojure.set :as set]
   [clojure.string]
   [cuerdas.core :as str]
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
    [:*
     [:span.char-box {:key unique-key} char]]))

(defn translation-keyname
  [type keyname]
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
        split-sc        (fn [sc]
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
        [:span.space (tr "shortcuts.or")]
        (for [char last-element]
          [:& converted-chars {:char char
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
    [:ul.sub-menu
     (for [command-translate sorted-filtered]
       (let [sc-by-translate  (first (filter #(= (:translation (second %)) command-translate) elements))
             [command  comand-info] sc-by-translate
             content                (:command comand-info)]
         [:li.shortcut-name {:key command-translate}
          [:span.command-name command-translate]
          [:& shortcuts-keys {:content content
                              :command command}]]))]))

(mf/defc section-title
  [{:keys [is-visible? name is-sub?] :as props}]
  [:div {:class (if is-sub? "subsection-title" "section-title")}
   [:span.collapesed-shortcuts {:class (when is-visible? "open")} i/arrow-slide]
   [:span {:class (if is-sub? "subsection-name" "section-name")} name]])

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

      [:ul.subsection-menu
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
      [:div {:on-click (manage-sections section-id)}
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
  []
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

        on-search-term-change
        (mf/use-callback
         (fn [event]
           (let [value (dom/get-target-val event)]
             (manage-sections-on-search value)
             (reset! filter-term value))))

        on-search-clear-click
        (mf/use-callback
         (fn [_]
           (reset! open-sections [[1]])
           (reset! filter-term "")))]

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
         :value @filter-term
         :on-change on-search-term-change
         :auto-complete "off"}]
       (if (str/empty? @filter-term)
         [:span.icon-wrapper
          i/search]
         [:span.icon-wrapper.close
          {:on-click on-search-clear-click}
          i/close])]]
     (if match-any?
       [:div.shortcut-list
        (for [section all-shortcuts]
          [:& shortcut-section
           {:section section
            :manage-sections manage-sections
            :open-sections open-sections
            :filter-term filter-term}])]

       [:div.not-found (tr "shortcuts.not-found")])]))
