;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.branches
  "Design branches PoC: a sidebar panel to list, open and create the
  branches of a file, review what a branch changed, resolve conflicts
  and merge it back."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.common :as dcm]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace :as dw]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.radio-buttons :refer [radio-buttons*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn toggle-panel
  [& _]
  (st/emit! (dw/remove-layout-flag :document-history)
            (dw/toggle-layout-flag :design-branches)))

(defn- close-panel
  []
  (st/emit! (dw/remove-layout-flag :design-branches)))

(defn- open-file
  [file-id]
  (st/emit! (dcm/go-to-workspace :file-id file-id)))

(defn- show-error
  [cause]
  (st/emit! (ntf/error (or (:hint (ex-data cause)) (ex-message cause) "Unexpected error"))))

(def ^:private geometry-attrs
  [:x :y :width :height :rotation])

(defn- d-name
  [v]
  (if (keyword? v) (name v) (str v)))

(defn- summarize
  [{:keys [group]} value]
  (cond
    (boolean? value) (if value "exists" "deleted")
    (= group :geometry-group) (pr-str (select-keys value geometry-attrs))
    :else (str/prune (pr-str value) 120)))

(def ^:private op-labels
  {:add "added" :del "removed" :mod "modified"})

(mf/defc change-row*
  {::mf/private true}
  [{:keys [change included on-toggle]}]
  (let [{:keys [key op kind name groups]} change
        on-change (mf/use-fn (mf/deps key on-toggle) #(on-toggle #{key}))]
    [:li {:class (stl/css-case :change true :excluded (not included))}
     [:div {:class (stl/css :change-title)}
      [:> checkbox* {:id key :checked included :on-change on-change}]
      [:span {:class (stl/css-case :op true
                                   :op-add (= op :add)
                                   :op-del (= op :del)
                                   :op-mod (= op :mod))}
       (get op-labels op)]
      [:span {:class (stl/css :name)} (or name "—")]
      [:span {:class (stl/css :muted)} (d-name kind)]]
     (when (seq groups)
       [:div {:class (stl/css :muted)} (str/join ", " (map d-name groups))])]))

(defn- group-changes
  "Group changes by the component they belong to; the rest go last."
  [changes]
  (->> changes
       (group-by :component-id)
       (map (fn [[component-id changes]]
              {:id (or (some-> component-id str) "other")
               :name (if component-id
                       (or (:component-name (first changes)) "Component")
                       "Other changes")
               :changes changes}))
       (sort-by (juxt #(= "other" (:id %)) :name))))

(mf/defc change-group*
  {::mf/private true}
  [{:keys [group excluded on-toggle]}]
  (let [{:keys [id name changes]} group
        ks        (mf/with-memo [changes] (into #{} (map :key) changes))
        included  (remove #(contains? excluded (:key %)) changes)
        all?      (= (count included) (count changes))
        on-change (mf/use-fn (mf/deps ks on-toggle all?) #(on-toggle ks (not all?)))]
    [:li {:class (stl/css :group)}
     [:div {:class (stl/css :group-title)}
      [:> checkbox* {:id (str "group-" id) :checked all? :on-change on-change}]
      [:span {:class (stl/css :name)} name]
      [:span {:class (stl/css :muted)} (str (count included) "/" (count changes))]]
     [:ul {:class (stl/css :list)}
      (for [change changes]
        [:> change-row* {:key (:key change)
                         :change change
                         :included (not (contains? excluded (:key change)))
                         :on-toggle on-toggle}])]]))

(mf/defc conflict-row*
  {::mf/private true}
  [{:keys [conflict selected on-change]}]
  (let [{:keys [id kind name group ours theirs]} conflict
        on-change (mf/use-fn (mf/deps id on-change) #(on-change id %))]
    [:li {:class (stl/css :conflict)}
     [:div {:class (stl/css :change-title)}
      [:span {:class (stl/css :name)} (or name "—")]
      [:span {:class (stl/css :muted)} (str (d-name kind) " · " (d-name group))]]
     [:dl {:class (stl/css :values)}
      [:dt "Core"]   [:dd (summarize conflict ours)]
      [:dt "Branch"] [:dd (summarize conflict theirs)]]
     [:> radio-buttons* {:name id
                         :extended true
                         :selected selected
                         :on-change on-change
                         :options [{:id (str id "-ours") :label "Keep core" :value "ours"}
                                   {:id (str id "-theirs") :label "Take branch" :value "theirs"}]}]]))

(mf/defc branch-review*
  {::mf/private true}
  [{:keys [branch]}]
  (let [diff*        (mf/use-state nil)
        diff         (deref diff*)
        resolutions* (mf/use-state {})
        resolutions  (deref resolutions*)
        excluded*    (mf/use-state #{})
        excluded     (deref excluded*)
        busy*        (mf/use-state false)
        busy         (deref busy*)

        file-id      (:file-id branch)
        changes      (:changes diff)
        groups       (mf/with-memo [changes] (group-changes changes))
        conflicts    (remove #(contains? excluded (:key %)) (:conflicts diff))
        resolved?    (every? #(contains? resolutions (:id %)) conflicts)
        selected     (- (count changes) (count (filter #(contains? excluded (:key %)) changes)))

        load-diff
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (reset! diff* nil)
           (reset! excluded* #{})
           (->> (rp/cmd! :get-file-branch-diff {:file-id file-id})
                (rx/subs! #(reset! diff* %) show-error))))

        on-resolve
        (mf/use-fn #(swap! resolutions* assoc %1 %2))

        on-toggle
        (mf/use-fn
         (fn
           ([ks]
            (swap! excluded* (fn [excluded]
                               (if (every? excluded ks)
                                 (reduce disj excluded ks)
                                 (into excluded ks)))))
           ([ks include?]
            (swap! excluded* #(if include? (reduce disj % ks) (into % ks))))))

        on-merge
        (mf/use-fn
         (mf/deps file-id resolutions excluded)
         (fn []
           (reset! busy* true)
           (->> (rp/cmd! :merge-file-branch {:file-id file-id
                                             :resolutions resolutions
                                             :excluded excluded})
                (rx/subs! (fn [{:keys [file-id]}]
                            (st/emit! (ntf/success "Branch merged into the core file"))
                            (open-file file-id))
                          (fn [cause]
                            (reset! busy* false)
                            (show-error cause))))))]

    (mf/with-effect [file-id]
      (load-diff))

    [:section {:class (stl/css :section)}
     [:div {:class (stl/css :section-title)}
      [:> text* {:as "h3" :typography t/headline-small}
       (if diff
         (str "Changes (" selected "/" (count changes) " selected)")
         "Changes")]
      [:> icon-button* {:variant "ghost"
                        :icon i/reload
                        :aria-label "Refresh changes"
                        :on-click load-diff}]]

     (cond
       (nil? diff)
       [:> text* {:as "p" :typography t/body-small :class (stl/css :muted)} "Computing changes…"]

       (empty? changes)
       [:> text* {:as "p" :typography t/body-small :class (stl/css :muted)} "Nothing changed yet."]

       :else
       [:ul {:class (stl/css :list)}
        (for [group groups]
          [:> change-group* {:key (:id group)
                             :group group
                             :excluded excluded
                             :on-toggle on-toggle}])])

     (when (seq conflicts)
       [:*
        [:> text* {:as "h3" :typography t/headline-small}
         (str "Conflicts (" (count conflicts) ")")]
        [:ul {:class (stl/css :list)}
         (for [conflict conflicts]
           [:> conflict-row* {:key (:id conflict)
                              :conflict conflict
                              :selected (get resolutions (:id conflict))
                              :on-change on-resolve}])]])

     [:> button* {:on-click on-merge
                  :variant "primary"
                  :class (stl/css :merge)
                  :disabled (or (nil? diff) (zero? selected) (not resolved?) busy)}
      (if (= selected (count changes))
        "Merge into core"
        (str "Merge " selected " selected"))]]))

(mf/defc file-row*
  {::mf/private true}
  [{:keys [file-id label status is-current on-delete]}]
  (let [on-click  (mf/use-fn (mf/deps file-id is-current) #(when-not is-current (open-file file-id)))
        on-delete (mf/use-fn (mf/deps file-id label on-delete) #(on-delete file-id label))]
    [:li {:class (stl/css :file-item)}
     [:button {:class (stl/css-case :file-row true :current is-current)
               :aria-current (when is-current "page")
               :on-click on-click}
      [:span {:class (stl/css :name)} label]
      [:span {:class (stl/css-case :status true
                                   :status-core (= status "core")
                                   :status-open (= status "open")
                                   :status-merged (= status "merged"))}
       status]]
     (when on-delete
       [:> icon-button* {:variant "ghost"
                         :icon i/delete
                         :aria-label (str "Delete branch " label)
                         :on-click on-delete}])]))

(mf/defc branches-panel*
  [{:keys [file-id]}]
  (let [info*  (mf/use-state nil)
        info   (deref info*)
        name*  (mf/use-state "")
        name   (deref name*)

        {:keys [branch core branches]} info

        load-info
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (->> (rp/cmd! :get-file-branches {:file-id file-id})
                (rx/subs! #(reset! info* %) show-error))))

        on-delete
        (mf/use-fn
         (mf/deps file-id load-info)
         (fn [branch-file-id label]
           (st/emit!
            (modal/show
             {:type :confirm
              :title "Delete branch"
              :message (str "Delete \"" label "\"? The branch file is deleted. "
                            "Merged changes stay in the core file.")
              :accept-label "Delete branch"
              :on-accept
              (fn []
                (->> (rp/cmd! :delete-file-branch {:file-id branch-file-id})
                     (rx/subs! (fn [{:keys [core-file-id]}]
                                 (if (= branch-file-id file-id)
                                   (open-file core-file-id)
                                   (load-info)))
                               show-error)))}))))

        on-name-change
        (mf/use-fn #(reset! name* (dom/get-target-val %)))

        on-create
        (mf/use-fn
         (mf/deps core name)
         (fn []
           (->> (rp/cmd! :create-file-branch {:file-id (:id core) :name (str/trim name)})
                (rx/subs! (fn [{:keys [file-id]}]
                            (reset! name* "")
                            (open-file file-id))
                          show-error))))]

    (mf/with-effect [file-id]
      (load-info))

    [:div {:class (stl/css :panel)}
     [:header {:class (stl/css :header)}
      [:> text* {:as "h2" :typography t/headline-small :class (stl/css :title)} "Branches"]
      [:> icon-button* {:variant "ghost"
                        :icon i/close
                        :aria-label "Close"
                        :on-click close-panel}]]

     (if (nil? info)
       [:> text* {:as "p" :typography t/body-small :class (stl/css :muted)} "Loading…"]
       [:div {:class (stl/css :content)}
        [:section {:class (stl/css :section)}
         [:ul {:class (stl/css :list)}
          [:> file-row* {:file-id (:id core)
                         :label (:name core)
                         :status "core"
                         :is-current (= file-id (:id core))}]
          (for [row branches]
            [:> file-row* {:key (str (:id row))
                           :file-id (:file-id row)
                           :label (:name row)
                           :status (:status row)
                           :is-current (= file-id (:file-id row))
                           :on-delete on-delete}])]

         [:div {:class (stl/css :create)}
          [:> input* {:placeholder "New branch name"
                      :value name
                      :on-change on-name-change}]
          [:> button* {:on-click on-create
                       :variant "secondary"
                       :disabled (str/blank? name)}
           "Create"]]]

        (when (= "open" (:status branch))
          [:> branch-review* {:key (str (:file-id branch)) :branch branch}])])]))
