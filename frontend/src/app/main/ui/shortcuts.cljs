;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shortcuts
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.dashboard.shortcuts.customize :as customize]
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as ds]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.strings :refer [matches-search]]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(def ^:private modifier-keys
  #{"Control" "Shift" "Alt" "Meta"})

(def ^:private key-name-map
  {"ArrowUp"    "up"
   "ArrowDown"  "down"
   "ArrowLeft"  "left"
   "ArrowRight" "right"
   "Escape"     "escape"
   "Enter"      "enter"
   "Backspace"  "backspace"
   "Delete"     "del"
   "Tab"        "tab"
   " "          "space"})

(defn- keyboard-event->mousetrap
  [^js event]
  (let [parts (cond-> []
                (and (.-ctrlKey event)
                     (not (cf/check-platform? :macos)))
                (conj "ctrl")

                (and (.-metaKey event)
                     (cf/check-platform? :macos))
                (conj "command")

                (.-altKey event)
                (conj "alt")

                (.-shiftKey event)
                (conj "shift"))
        key   (.-key event)
        key   (if (contains? modifier-keys key)
                nil
                (or (get key-name-map key)
                    (.toLowerCase key)))]
    (when key
      (str/join "+" (conj parts key)))))

(defn- keyboard-event->display-parts
  [^js event]
  (let [parts (cond-> []
                (and (.-ctrlKey event)
                     (not (cf/check-platform? :macos)))
                (conj "ctrl")

                (and (.-metaKey event)
                     (cf/check-platform? :macos))
                (conj "command")

                (.-altKey event)
                (conj "alt")

                (.-shiftKey event)
                (conj "shift"))
        key   (.-key event)]
    (if (contains? modifier-keys key)
      {:modifiers parts :finalized? false}
      {:modifiers parts
       :final-key (or (get key-name-map key) (.toLowerCase key))
       :finalized? true})))

(defn translation-keyname
  [type keyname]
  (let [translat-pre (case type
                       :sc      "shortcuts."
                       :sec     "shortcut-section."
                       :sub-sec "shortcut-subsection.")]
    (tr (str translat-pre (d/name keyname)))))

(defn- find-conflict
  [new-command all-shortcuts current-key]
  (let [command-index (ds/build-command-index all-shortcuts)]
    (when-let [conflicting-key (get command-index new-command)]
      (when (not= conflicting-key current-key)
        {:key  conflicting-key
         :name (translation-keyname :sc conflicting-key)}))))

(def ^:private import-contexts
  [:workspace :dashboard :viewer])

(defn- import-context-group
  "Imports a single context group from the payload, disabling any default
   shortcut whose command collides with a newly imported one, and any
   previously-imported entry in the same batch with a duplicate command."
  [group all-shortcuts]
  (reduce
   (fn [acc [command recorded-command]]
     (let [default-conflict (find-conflict recorded-command all-shortcuts command)
           acc-conflict     (some (fn [[k v]]
                                    (when (and (not= k command) (= v recorded-command))
                                      k))
                                  acc)]
       (cond-> (assoc acc command recorded-command)
         (:key default-conflict)
         (assoc (:key default-conflict) "")
         acc-conflict
         (assoc acc-conflict ""))))
   {}
   group))

(defn import-custom-shortcuts
  [shortcuts all-shortcuts]
  (let [current-customs (or (get-in shortcuts [:profile :props :custom-shortcuts])
                            (get-in @st/state [:profile :props :custom-shortcuts])
                            {})

        new-customs (reduce
                     (fn [acc ctx]
                       (if (contains? shortcuts ctx)
                         (assoc acc ctx (import-context-group (get shortcuts ctx) all-shortcuts))
                         acc))
                     current-customs
                     import-contexts)]

    (du/update-profile-props {:custom-shortcuts new-customs})))

(defn add-translation
  [type item]
  (map (fn [[k v]] [k (assoc v :translation (translation-keyname type k))]) item))

(defn shortcuts->subsections
  [shortcuts]
  (let [subsections (into #{} (mapcat :subsections) (vals shortcuts))
        subsections (vec (sort-by name subsections))
        get-sc-by-subsection
        (fn [subsection [k v]]
          (when (some #(= subsection %) (:subsections v)) {k v}))
        reduce-sc
        (fn [acc subsection]
          (let [shortcuts-by-subsection (into {} (keep (partial get-sc-by-subsection subsection) shortcuts))]
            (assoc acc subsection {:children shortcuts-by-subsection})))]
    (reduce reduce-sc (sorted-map-by (fn [a b] (compare (name a) (name b)))) subsections)))

(defn build-all-shortcuts
  [workspace-sc dashboard-sc viewer-sc]
  (let [walk (fn walk [element parent-id]
               (if (nil? element)
                 element
                 (let [sorted-comp (when (sorted? element)
                                     #(compare (name %1) (name %2)))
                       rec-fn (fn [[k item]]
                                (let [item-id (if (nil? parent-id)
                                                [k]
                                                (conj parent-id k))]
                                  [k (assoc item :id item-id :children (walk (:children item) item-id))]))]
                   (if sorted-comp
                     (into (sorted-map-by sorted-comp) (map rec-fn element))
                     (into {} (map rec-fn element))))))
        sorted-map-fn #(sorted-map-by (fn [a b] (compare (name a) (name b))))
        ws-subs (->> (shortcuts->subsections workspace-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))

        db-subs (->> (shortcuts->subsections dashboard-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))
        vw-subs (->> (shortcuts->subsections viewer-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))
        basics  (into {} (concat (:children (:basics ws-subs))
                                 (:children (:basics db-subs))
                                 (:children (:basics vw-subs))))
        ws-subs (dissoc ws-subs :basics)
        db-subs (dissoc db-subs :basics)
        vw-subs (dissoc vw-subs :basics)
        all     {:basics    {:children {:none {:children basics}}
                             :translation (tr "shortcut-section.basics")}
                 :workspace {:children ws-subs
                             :translation (tr "shortcut-section.workspace")}
                 :dashboard {:children db-subs
                             :translation (tr "shortcut-section.dashboard")}
                 :viewer    {:children vw-subs
                             :translation (tr "shortcut-section.viewer")}}
        all     (walk all nil)
        all-sc-names (map #(translation-keyname :sc %)
                          (concat (keys workspace-sc)
                                  (keys dashboard-sc)
                                  (keys viewer-sc)))
        all-sub-names (map #(translation-keyname :sub-sec %)
                           (concat (keys ws-subs)
                                   (keys db-subs)
                                   (keys vw-subs)))
        all-section-names (map #(translation-keyname :sec %) (keys all))]
    {:all-shortcuts all
     :all-sc-names all-sc-names
     :all-sub-names all-sub-names
     :all-section-names all-section-names}))

(defn build-all-shortcuts-without-basics
  [workspace-sc path-sc dashboard-sc viewer-sc
   & {:keys [workspace-orig path-orig dashboard-orig viewer-orig]}]
  (let [original-commands (into {}
                                (concat
                                 (map (fn [[k v]] [k (:command v)]) workspace-orig)
                                 (map (fn [[k v]] [k (:command v)]) path-orig)
                                 (map (fn [[k v]] [k (:command v)]) dashboard-orig)
                                 (map (fn [[k v]] [k (:command v)]) viewer-orig)))
        walk (fn walk [element parent-id]
               (if (nil? element)
                 element
                 (let [sorted-comp (when (sorted? element)
                                     #(compare (name %1) (name %2)))
                       rec-fn (fn [[k item]]
                                (let [item-id (if (nil? parent-id)
                                                [k]
                                                (conj parent-id k))
                                      item (if (:command item)
                                             (assoc item :original-command (get original-commands k (:command item)))
                                             item)]
                                  [k (assoc item :id item-id :children (walk (:children item) item-id))]))]
                   (if sorted-comp
                     (into (sorted-map-by sorted-comp) (map rec-fn element))
                     (into {} (map rec-fn element))))))
        sorted-map-fn #(sorted-map-by (fn [a b] (compare (name a) (name b))))
        ws-subs (->> (shortcuts->subsections workspace-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))

        pt-subs (->> (shortcuts->subsections path-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))
        db-subs (->> (shortcuts->subsections dashboard-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))
        vw-subs (->> (shortcuts->subsections viewer-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))
        ws-subs  (dissoc ws-subs :basics)
        pt-subs  (dissoc pt-subs :basics)
        db-subs  (dissoc db-subs :basics)
        vw-subs  (dissoc vw-subs :basics)
        ws-subs  (merge ws-subs pt-subs)
        all      {:workspace {:children ws-subs
                              :translation (tr "shortcut-section.workspace")}
                  :dashboard {:children db-subs
                              :translation (tr "shortcut-section.dashboard")}
                  :viewer    {:children vw-subs
                              :translation (tr "shortcut-section.viewer")}}
        all      (walk all nil)
        all-sc-names (map #(translation-keyname :sc %)
                          (concat (keys workspace-sc)
                                  (keys path-sc)
                                  (keys dashboard-sc)
                                  (keys viewer-sc)))
        all-sub-names (map #(translation-keyname :sub-sec %)
                           (concat (keys ws-subs)
                                   (keys db-subs)
                                   (keys vw-subs)))
        all-section-names (map #(translation-keyname :sec %) (keys all))]
    {:all-shortcuts all
     :all-sc-names all-sc-names
     :all-sub-names all-sub-names
     :all-section-names all-section-names}))

(mf/defc converted-chars*
  [{:keys [char command class]}]
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
    [:span {:class [class (stl/css :key)]
            :key unique-key} char]))

(mf/defc shortcuts-keys*
  [{:keys [content command is-customized has-conflict?]}]
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
       [:* {:key (str/join chars)}
        (for [char chars]
          [:> converted-chars* {:key (dm/str char "-" (name command))
                                :char char
                                :command command
                                :class (cond
                                         has-conflict? (stl/css :conflict-key)
                                         is-customized (stl/css :customized-key))}])

        (when (not= chars penultimate) [:span {:class (stl/css :space)} ","])])
     (when (not= last-element penultimate)
       [:*
        [:span {:class (stl/css :space)} (tr "shortcuts.or")]
        (for [char last-element]
          [:> converted-chars* {:key (dm/str char "-" (name command))
                                :char char
                                :command command
                                :class (cond
                                         has-conflict? (stl/css :conflict-key)
                                         is-customized (stl/css :customized-key))}])])]))

(mf/defc shortcut-row-editable*
  [{:keys [elements custom-shortcuts command-translate conflicts section-key]}]
  (let [{:keys [workspace-sc-trans dashboard-sc-trans viewer-sc-trans]} (mf/use-ctx ctx/shortcuts-ctx)
        section-shortcuts (case section-key
                            (:workspace :path) workspace-sc-trans
                            :dashboard dashboard-sc-trans
                            :viewer viewer-sc-trans
                            workspace-sc-trans)
        sc-by-translate  (first (filter #(= (:translation (second %)) command-translate) elements))
        [command  command-info] sc-by-translate
        content                (or (:show-command command-info) (:command command-info))
        group-map              (if (= section-key :basics)
                                 (merge (get custom-shortcuts :workspace)
                                        (get custom-shortcuts :dashboard)
                                        (get custom-shortcuts :viewer))
                                 (get custom-shortcuts section-key))
        group-map              (if (map? group-map) group-map {})
        customized?            (contains? group-map command)
        has-conflict?          (contains? conflicts command)
        is-editing*            (mf/use-state false)
        is-editing             (deref is-editing*)

        recording-ref          (mf/use-ref nil)

        display-parts*         (mf/use-state nil)
        display-parts          (deref display-parts*)

        recorded-command*      (mf/use-state nil)
        recorded-command       (deref recorded-command*)

        conflict*              (mf/use-state nil)
        conflict               (deref conflict*)

        clean-editing-state
        (fn []
          (reset! is-editing* false)
          (reset! display-parts* nil)
          (reset! recorded-command* nil)
          (reset! conflict* nil))

        effective-section-key (if (= section-key :basics) :workspace section-key)

        on-reset-shortcut
        (mf/use-fn
         (mf/deps effective-section-key command-info)
         (fn [shortcut-key]
           (st/emit! (customize/reset-custom-shortcut shortcut-key (:original-command command-info) effective-section-key))
           (clean-editing-state)))

        start-editing
        (mf/use-fn
         (fn [_]
           (reset! is-editing* true)
           (reset! display-parts* nil)
           (reset! recorded-command* nil)
           (reset! conflict* nil)))

        stop-editing
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (clean-editing-state)))

        disabled-shortcut
        (mf/use-fn
         (mf/deps command effective-section-key)
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (customize/set-custom-shortcut
                      command
                      ""
                      nil
                      effective-section-key))
           (clean-editing-state)))

        save-fn
        (mf/use-fn
         (mf/deps recorded-command conflict command effective-section-key)
         (fn [event]
           (dom/prevent-default event)
           (when recorded-command
             (st/emit! (customize/set-custom-shortcut
                        command
                        recorded-command
                        (:key conflict)
                        effective-section-key)))
           (clean-editing-state)))

        on-editable-container-blur
        (mf/use-fn
         (fn [e]
           (when-not (.contains (.-currentTarget e)
                                (.-relatedTarget e))
             (clean-editing-state))))]

    (mf/with-effect [is-editing]
      (when is-editing
        (some-> (mf/ref-val recording-ref) (dom/focus!))))

    (mf/with-effect [is-editing]
      (when is-editing
        (letfn [(on-keydown [^js event]
                  (.preventDefault event)
                  (.stopPropagation event)
                  (let [recorded-command (keyboard-event->mousetrap event)
                        parts   (keyboard-event->display-parts event)]
                    (reset! display-parts* parts)
                    (when recorded-command
                      (reset! recorded-command* recorded-command)
                      (reset! conflict* (find-conflict recorded-command section-shortcuts command)))))]
          (->> (events/listen (mf/ref-val recording-ref) "keydown" on-keydown)
               (partial events/unlistenByKey)))))


    [:li {:class (stl/css-case :shortcuts-name-editable true
                               :shortcuts-editing is-editing
                               :customized customized?)
          :data-customized (str customized?)
          :data-conflict (str has-conflict?)
          :aria-label command-translate
          :key command-translate}
     [:button {:on-click start-editing
               :disabled is-editing
               :aria-label (dm/str "Edit " command-translate)
               :class (stl/css-case :shortcut-button-editable true
                                    :shortcut-button-editing is-editing)}
      [:span {:class (stl/css :command-name)
              :id (dm/str command-translate "-label")}
       command-translate]
      [:div {:class (stl/css :shortcut-actions)
             :aria-labelledby (dm/str command-translate "-label")}
       (if (and customized? (str/blank? content))
         [:> icon* {:icon-id i/broken-link
                    :size "s"
                    :class (stl/css :shortcut-detach-icon)}]

         (if has-conflict?
           (let [conflicting-key (get conflicts command)
                 conflict-name   (translation-keyname :sc conflicting-key)]
             [:> tooltip* {:content (tr  "shortcuts.overwritten-by" conflict-name)
                           :id (dm/str (name command) "-conflict-tooltip")
                           :class (stl/css :option-text)}
              [:> shortcuts-keys* {:content content
                                   :command command
                                   :is-customized customized?
                                   :has-conflict? has-conflict?}]])
           [:> shortcuts-keys* {:content content
                                :command command
                                :is-customized customized?
                                :has-conflict? has-conflict?}]))]]
     (when is-editing
       [:div {:class (stl/css :shortcut-editing)
              :ref recording-ref
              :tab-index 0
              :on-blur on-editable-container-blur}
        [:div {:class (stl/css :shortcut-recording-area)}
         (if (nil? display-parts)
           [:span {:class (stl/css :shortcut-placeholder)}
            (tr "shortcuts.key-combo")]
           [:span {:class (stl/css :shortcut-recorded-keys)}
            (for [mod (:modifiers display-parts)]
              [:span {:class (stl/css-case :key true
                                           :customized-key customized?) :key mod} mod])
            (when (:final-key display-parts)
              [:span {:class (stl/css-case :key true
                                           :customized-key customized?)} (:final-key display-parts)])
            (when-not (:finalized? display-parts)
              [:span {:class (stl/css :recording-ellipsis)} "..."])])]
        (when recorded-command
          (if conflict
            [:> context-notification* {:level :warning :class (stl/css :modal-error-msg)}
             (tr "shortcuts.edit-modal.conflict" (:name conflict))]
            [:> context-notification* {:level :success :class (stl/css :modal-success-msg)}
             (tr "shortcuts.edit-modal.success")]))
        [:div {:class (stl/css :edit-buttons)}
         [:div {:class (stl/css :confirmation-buttons)}
          [:> icon-button* {:variant "secondary"
                            :aria-label (tr "labels.cancel")
                            :on-click stop-editing
                            :icon i/close
                            :icon-size "m"}]
          [:> icon-button* {:variant "primary"
                            :aria-label (tr "labels.save")
                            :on-click save-fn
                            :icon i/tick
                            :icon-size "m"}]]
         [:div {:class (stl/css :confirmation-buttons)}
          [:> icon-button* {:variant "secondary"
                            :aria-label (tr "shortcuts.disable")
                            :on-click (fn [e]
                                        (dom/stop-propagation e)
                                        (disabled-shortcut command))
                            :icon i/broken-link
                            :icon-size "m"}]
          (when customized?
            [:> icon-button* {:variant "secondary"
                              :aria-label (tr "shortcuts.reset")
                              :on-click (fn [e]
                                          (dom/stop-propagation e)
                                          (on-reset-shortcut command))
                              :icon i/reload
                              :icon-size "m"}])]]])]))

(mf/defc shortcut-row*
  [{:keys [elements filter-term is-match-section is-match-subsection
           editable? custom-shortcuts section-key conflicts hidden subsection-name]}]
  (let [shortcut-translations (->> elements vals (map :translation) sort)
        match-shortcut?       (some #(matches-search % filter-term) shortcut-translations)
        filtered              (if (and (or is-match-section is-match-subsection) (not match-shortcut?))
                                shortcut-translations
                                (filter #(matches-search % filter-term) shortcut-translations))
        sorted-filtered       (sort filtered)
        trigger-ref            (mf/use-ref nil)]

    [:ul {:class (stl/css :sub-menu)
          :id (dm/str (d/name subsection-name) "-subsection")
          :hidden hidden}
     (for [command-translate sorted-filtered]
       (let [sc-by-translate  (first (filter #(= (:translation (second %)) command-translate) elements))
             [command  command-info] sc-by-translate
             content                (or (:show-command command-info) (:command command-info))
             group-map              (if (= section-key :basics)
                                      (merge (get custom-shortcuts :workspace)
                                             (get custom-shortcuts :dashboard)
                                             (get custom-shortcuts :viewer))
                                      (get custom-shortcuts section-key))
             group-map              (if (map? group-map) group-map {})
             customized?            (contains? group-map command)
             has-conflict?          (contains? conflicts command)]
         (if editable?
           [:> shortcut-row-editable* {:elements elements
                                       :custom-shortcuts custom-shortcuts
                                       :section-key section-key
                                       :key command-translate
                                       :command-translate command-translate
                                       :conflicts conflicts}]
           [:li {:class (stl/css-case :shortcuts-name true
                                      :customized customized?)
                 :data-customized (str customized?)
                 :data-conflict (str has-conflict?)
                 :aria-label command-translate
                 :key command-translate}
            [:span {:class (stl/css :command-name)
                    :id (dm/str command-translate "-label")}
             command-translate]
            [:div {:class (stl/css :shortcut-actions)
                   :aria-labelledby (dm/str command-translate "-label")}
             (if (and customized? (str/blank? content))
               [:> tooltip* {:content (tr "shortcuts.disabled-shortcut")
                             :trigger-ref trigger-ref
                             :id (dm/str command-translate "-tooltip")
                             :class (stl/css :option-text)}
                [:> icon* {:icon-id i/broken-link
                           :aria-labelledby (dm/str command-translate "-tooltip")
                           :size "s"
                           :ref trigger-ref
                           :class (stl/css :shortcut-detach-icon)}]]
               (if has-conflict?
                 (let [conflicting-key (get conflicts command)
                       conflict-name   (translation-keyname :sc conflicting-key)]
                   [:> tooltip* {:content (tr "shortcuts.overwritten-by" conflict-name)
                                 :id (dm/str (name command) "-conflict-tooltip")
                                 :class (stl/css :option-text)}
                    [:> shortcuts-keys* {:content content
                                         :command command
                                         :is-customized customized?
                                         :has-conflict? has-conflict?}]])
                 [:> shortcuts-keys* {:content content
                                      :command command
                                      :is-customized customized?
                                      :has-conflict? has-conflict?}]))]])))]))


(mf/defc section-title*
  [{:keys [name is-visible is-sub on-click]}]
  (let [handle-key-down
        (fn [event]
          (when (or (= "Enter" (.-key event))
                    (= " " (.-key event)))
            (.preventDefault event)
            (on-click event)))]
    [:button {:class (if is-sub
                       (stl/css :subsection-title)
                       (stl/css :section-title))
              :on-click on-click
              :on-key-down handle-key-down
              :aria-expanded is-visible
              :aria-controls (dm/str (str/lower name) (if is-sub "-subsection" "-section"))}
     [:> icon* {:icon-id (if is-visible i/arrow-down i/arrow-right)
                :size "s"
                :aria-hidden true}]
     [:span {:class (if is-sub
                      (stl/css :subsection-name)
                      (stl/css :section-name))} name]]))

(mf/defc shortcut-subsection*
  [{:keys [subsections manage-sections filter-term is-match-section open-sections
           editable? custom-shortcuts section-key conflicts hidden]}]
  (if (= :none (first (keys subsections)))
    [:> shortcut-row* {:elements (:children (:none subsections))
                       :filter-term filter-term
                       :is-match-section is-match-section
                       :is-match-subsection true
                       :editable? editable?
                       :hidden hidden
                       :custom-shortcuts custom-shortcuts
                       :section-key section-key
                       :conflicts conflicts}]

    [:ul {:class (stl/css :subsection-menu)
          :hidden hidden
          :id (dm/str (name section-key) "-section")}
     (let [sorted-subs (sort-by (fn [[_ v]] (:translation v)) subsections)]
       (for [[sub-name sub-info] sorted-subs]
         (let [visible? (some #(= % (:id sub-info)) open-sections)]
           [:li {:key (name sub-name)}
            [:h4
             [:> section-title* {:name (:translation sub-info)
                                 :is-visible visible?
                                 :is-sub true
                                 :on-click (manage-sections (:id sub-info))}]]

            [:> shortcut-row* {:elements (:children sub-info)
                               :subsection-name sub-name
                               :filter-term filter-term
                               :is-match-section is-match-section
                               :is-match-subsection true
                               :editable? editable?
                               :hidden (not visible?)
                               :custom-shortcuts custom-shortcuts
                               :section-key section-key
                               :conflicts conflicts}]])))]))



(mf/defc shortcut-section*
  [{:keys [section manage-sections open-sections filter-term
           editable? custom-shortcuts conflicts]}]
  (let [[section-key section-info] section
        section-id          (:id section-info)
        section-translation (:translation section-info)
        subsections         (:children section-info)
        visible?            (some #(= % section-id) open-sections)]
    [:div {:class (stl/css :section)}
     [:h3 {:class (stl/css :section-header)}
      [:> section-title* {:name section-translation
                          :is-visible visible?
                          :is-sub false
                          :on-click (manage-sections section-id)}]]

     [:> shortcut-subsection* {:subsections subsections
                               :open-sections open-sections
                               :manage-sections manage-sections
                               :is-match-section false
                               :filter-term filter-term
                               :editable? editable?
                               :hidden (not visible?)
                               :custom-shortcuts custom-shortcuts
                               :section-key section-key
                               :conflicts conflicts}]]))
