;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.branches
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.branch-merge :as bm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.branches :as dwb]
   [app.main.data.workspace.pull-requests :as dwpr]
   [app.main.refs :as refs]
   [app.main.render :as render]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.workspace.sidebar.pull-requests :as prui :refer [pr-state-badge*]]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private branches
  (l/derived :workspace-branches st/state))

(def ^:private branch-diff
  (l/derived :workspace-branch-diff st/state))

(def ^:private branch-context
  ;; see refs/branch-context: the state entry wraps the row under :info
  (l/derived #(get-in % [:workspace-branch-context :info]) st/state))

(def ^:private kind->icon
  {:shape               i/board
   :component           i/component
   :color               i/picker
   :typography          i/text
   :media               i/img
   :page                i/document
   :page-attrs          i/document
   :page-order          i/document
   :page-guide          i/document
   :page-flow           i/document
   :page-grid           i/document
   :page-plugin         i/document
   :token               i/tokens
   :token-set           i/tokens
   :token-set-rename    i/tokens
   :token-set-order     i/tokens
   :token-theme         i/tokens
   :token-active-themes i/tokens
   :token-active-sets   i/tokens})

(def ^:private status->label
  {:added    "workspace.branches.status.added"
   :modified "workspace.branches.status.modified"
   :deleted  "workspace.branches.status.deleted"
   :conflict "workspace.branches.status.conflict"})

(defn- short-str
  [v]
  (let [s (pr-str v)]
    (if (> (count s) 40)
      (str (subs s 0 40) "…")
      s)))

(def ^:private attr->label
  "Friendlier names for attrs whose raw key is unclear; every other attr
  is humanized generically (kebab-case -> Capitalized words)."
  {:rx "Corner radius" :ry "Corner radius"
   :grow-type "Text auto-grow" :blend-mode "Blend mode"
   :shape-ref "Component reference" :component-id "Component"
   :component-file "Component library" :hidden "Visibility"
   :blocked "Locked" :proportion-lock "Lock proportions"
   :parent-id "Parent" :frame-id "Container"})

(defn- attr-label
  "Human-readable label for a changed property key."
  [attr]
  (or (get attr->label attr)
      (-> (name attr) (str/replace #"-" " ") str/capital)))

;; --- Compare: grouping by category + per-item type labels

(def ^:private kind->category
  {:shape :pages :page :pages :page-attrs :pages :page-order :pages
   :page-guide :pages :page-flow :pages :page-grid :pages :page-plugin :pages
   :component :components
   :color :colors
   :typography :typographies
   :media :media
   :token :tokens :token-set :tokens :token-set-rename :tokens :token-set-order :tokens
   :token-theme :tokens :token-active-themes :tokens :token-active-sets :tokens})

(def ^:private category-order [:pages :components :colors :typographies :media :tokens])

(def ^:private category->icon
  {:pages i/document :components i/component :colors i/picker
   :typographies i/text :media i/img :tokens i/tokens})

(def ^:private category->label
  {:pages "workspace.branches.compare.group.pages"
   :components "workspace.branches.compare.group.components"
   :colors "workspace.branches.compare.group.colors"
   :typographies "workspace.branches.compare.group.typographies"
   :media "workspace.branches.compare.group.media"
   :tokens "workspace.branches.compare.group.tokens"})

(def ^:private kind->type-label
  {:shape "workspace.branches.compare.type.shape"
   :component "workspace.branches.compare.type.component"
   :color "workspace.branches.compare.type.color"
   :typography "workspace.branches.compare.type.typography"
   :media "workspace.branches.compare.type.media"
   :token "workspace.branches.compare.type.token"})

(def ^:private shape-type->icon
  {:frame "board" :rect "rectangle" :line "rectangle" :circle "ellipse"
   :path "path" :text "text" :image "img" :svg-raw "img" :group "group"
   :bool "boolean-union"})

(defn- shape-icon-id
  "Type-accurate icon for a shape diff entry (component nature wins over
  raw type), falling back to the board icon for unknown types."
  [{:keys [shape-type component? component-copy? variant? masked?]}]
  (cond
    variant?        "variant"
    component?      "component"
    component-copy? "component-copy"
    (and (= :group shape-type) masked?) "mask"
    :else           (get shape-type->icon shape-type "board")))

(def ^:private shape-type->label
  {:frame "workspace.branches.compare.shape.board"
   :rect "workspace.branches.compare.shape.rect"
   :line "workspace.branches.compare.shape.rect"
   :circle "workspace.branches.compare.shape.ellipse"
   :path "workspace.branches.compare.shape.path"
   :text "workspace.branches.compare.shape.text"
   :image "workspace.branches.compare.shape.image"
   :svg-raw "workspace.branches.compare.shape.image"
   :group "workspace.branches.compare.shape.group"
   :bool "workspace.branches.compare.shape.bool"})

(defn- type-label-key
  "i18n key for an item's specific type — type-accurate for shapes
  (board/text/rectangle/component/…), generic for other kinds."
  [item]
  (if (= :shape (:kind item))
    (let [{:keys [component? component-copy? variant? shape-type]} item]
      (cond
        variant?        "workspace.branches.compare.shape.variant"
        component?      "workspace.branches.compare.shape.component"
        component-copy? "workspace.branches.compare.shape.component-copy"
        :else           (get shape-type->label shape-type
                             "workspace.branches.compare.type.shape")))
    (get kind->type-label (:kind item))))

(defn- item-status
  "Conflicts carry `:conflict`; otherwise the regular add/mod/del status."
  [item]
  (if (= :conflict (:status item)) :conflict (:status item)))

(defn- hex-color
  "Return a usable hex string when `v` looks like a color (a hex string, a
  color map, or a fills/strokes vector), else nil."
  [v]
  (let [s (cond
            (string? v) v
            (and (map? v) (string? (:color v))) (:color v)
            (and (map? v) (string? (:fill-color v))) (:fill-color v)
            (and (map? v) (string? (:stroke-color v))) (:stroke-color v)
            (and (sequential? v) (map? (first v)))
            (or (:fill-color (first v)) (:stroke-color (first v)) (:color (first v)))
            :else nil)]
    ;; only valid hex lengths (3/4/6/8 digits); a bare string must carry
    ;; the leading `#`, otherwise casual words like "cafe" become swatches
    (when (and (string? s)
               (re-matches #"#[0-9a-fA-F]{8}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3,4}" s))
      s)))

(defn- fmt-number
  "At most two decimals, trailing zeros stripped (89.99999 -> \"90\",
  439.4999 -> \"439.5\", 386 -> \"386\")."
  [v]
  (-> (.toFixed v 2)
      (str/replace #"\.?0+$" "")))

(defn- display-val
  "Compact, human-readable rendering of a property value. Raw uuids and
  collections are summarized rather than dumped (they carry no meaning to
  the user as raw data)."
  [v]
  (cond
    (nil? v)         "—"
    (string? v)      (if (> (count v) 32) (str (subs v 0 32) "…") v)
    (number? v)      (fmt-number v)
    (boolean? v)     (if v "true" "false")
    (keyword? v)     (name v)
    (ct/inst? v)     (ct/format-inst v :localized-date-time)
    (uuid? v)        (str (subs (str v) 0 8) "…")
    (sequential? v)  (tr "workspace.branches.compare.n-items" (dm/str (count v)))
    (map? v)         (tr "workspace.branches.compare.n-props" (dm/str (count v)))
    :else            (short-str v)))

(defn- status-matches?
  [filter status]
  (case filter
    :all      true
    :added    (= status :added)
    :modified (contains? #{:modified :conflict} status)
    :deleted  (= status :deleted)
    true))

(def ^:private compare-filters
  [[:all "labels.all"]
   [:added "workspace.branches.status.added"]
   [:modified "workspace.branches.status.modified"]
   [:deleted "workspace.branches.status.deleted"]])

;; --- Create branch dialog (modal)

(mf/defc create-branch-dialog*
  {::mf/register modal/components
   ::mf/register-as :create-branch}
  [{:keys [file-name file-id]}]
  (let [branch-name* (mf/use-state "")
        description*  (mf/use-state "")
        branch-name   (deref branch-name*)
        description   (deref description*)
        valid?        (not (str/blank? branch-name))

        on-name-change
        (mf/use-fn #(reset! branch-name* (dom/get-target-val %)))

        on-description-change
        (mf/use-fn #(reset! description* (dom/get-target-val %)))

        on-close
        (mf/use-fn #(st/emit! (modal/hide)))

        on-submit
        (mf/use-fn
         (mf/deps branch-name description valid? file-id)
         (fn [_]
           (when valid?
             (st/emit! (dwb/create-branch file-id (str/trim branch-name) (str/trim description))
                       (modal/hide)))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:div {:class (stl/css :modal-header-icon)}
        [:> i/icon* {:icon-id i/git-branch}]]
       [:div {:class (stl/css :modal-header-text)}
        [:h2 {:class (stl/css :modal-title)} (tr "workspace.branches.create.title")]
        [:span {:class (stl/css :modal-subtitle)}
         (tr "workspace.branches.create.subtitle" (or file-name ""))]]
       [:> button* {:variant "ghost"
                    :icon i/close
                    :aria-label (tr "labels.close")
                    :on-click on-close}]]

      [:div {:class (stl/css :modal-content)}
       [:> input* {:label (tr "workspace.branches.create.name-label")
                   :icon i/git-branch
                   :placeholder (tr "workspace.branches.create.name-placeholder")
                   :value branch-name
                   :on-change on-name-change}]

       [:div {:class (stl/css :field)}
        [:label {:class (stl/css :field-label)}
         (tr "workspace.branches.create.description-label")]
        [:textarea {:class (stl/css :textarea)
                    :value description
                    :on-change on-description-change
                    :rows 2}]]

       [:div {:class (stl/css :field)}
        [:label {:class (stl/css :field-label)}
         (tr "workspace.branches.create.derive-from")]
        [:div {:class (stl/css :derive-from)}
         [:span {:class (stl/css :derive-from-dot)}]
         [:span {:class (stl/css :derive-from-name)}
          (tr "workspace.branches.create.derive-from-main")]
         [:span {:class (stl/css :derive-from-hint)}
          (tr "workspace.branches.create.derive-from-latest")]]]

       [:> context-notification* {:level :info :type :context}
        (tr "workspace.branches.create.info")]]

      [:div {:class (stl/css :modal-footer)}
       [:> button* {:variant "secondary" :on-click on-close}
        (tr "labels.cancel")]
       [:> button* {:variant "primary"
                    :icon i/git-branch-plus
                    :disabled (not valid?)
                    :on-click on-submit}
        (tr "workspace.branches.create.submit")]]]]))

;; --- Merge confirmation dialog (modal)

(mf/defc merge-branch-dialog*
  {::mf/register modal/components
   ::mf/register-as :merge-branch}
  [{:keys [branch]}]
  (let [archive?* (mf/use-state false)
        archive?  (deref archive?*)

        ;; The branch name and "main" are highlighted (foreground-primary)
        ;; within the otherwise secondary-colored prompt. We wrap each of
        ;; them with a sentinel in the `tr` args and split on it, so the
        ;; whole sentence stays in a single translation and the (user
        ;; controlled) branch name is rendered as escaped text, not HTML.
        message-parts
        (let [sep "\uE000"
              msg (tr "workspace.branches.merge.confirm-message"
                      (str sep "\"" (:name branch) "\"" sep)
                      (str sep (tr "workspace.branches.main") sep))]
          (str/split msg sep))

        on-toggle-archive
        (mf/use-fn #(swap! archive?* not))

        on-close
        (mf/use-fn #(st/emit! (modal/hide)))

        on-submit
        (mf/use-fn
         (mf/deps branch archive?)
         (fn [_]
           (st/emit! (dwb/merge-branch branch {:keep-branch archive?})
                     (modal/hide))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} (tr "workspace.branches.merge.confirm-title")]
       [:> button* {:variant "ghost"
                    :icon i/close
                    :aria-label (tr "labels.close")
                    :on-click on-close}]]

      [:div {:class (stl/css :modal-content)}
       [:p {:class (stl/css :modal-message)}
        (for [[idx part] (map-indexed vector message-parts)]
          (if (odd? idx)
            [:span {:key idx :class (stl/css :modal-message-emphasis)} part]
            [:span {:key idx} part]))]

       [:> checkbox* {:id "merge-archive-branch"
                      :label (tr "workspace.branches.merge.archive-label")
                      :checked archive?
                      :on-change on-toggle-archive}]

       (if archive?
         [:> context-notification* {:level :warning :type :context}
          (tr "workspace.branches.merge.archive-warning")]
         [:> context-notification* {:level :info :type :context}
          (tr "workspace.branches.merge.delete-info")])]

      [:div {:class (stl/css :modal-footer)}
       [:> button* {:variant "secondary" :on-click on-close}
        (tr "labels.cancel")]
       [:> button* {:variant "primary"
                    :icon i/git-merge
                    :on-click on-submit}
        (tr "workspace.branches.merge.confirm-accept")]]]]))

;; --- Confirmation for relevant branch actions (merge / update)

(defn- confirm-merge!
  "Open the merge dialog for `branch` (merge into main is irreversible; the
  dialog also lets the user choose whether to keep the branch archived)."
  [branch]
  (modal/show! :merge-branch {:branch branch}))

(defn- confirm-update!
  "Ask for confirmation before updating `branch` from main (overwrites
  conflicting branch changes)."
  [branch]
  (st/emit! (modal/show {:type :confirm
                         :title (tr "workspace.branches.update.confirm-title")
                         :message (tr "workspace.branches.update.confirm-message" (:name branch))
                         :accept-label (tr "workspace.branches.update.confirm-accept")
                         :accept-style :primary
                         :on-accept (fn [_] (st/emit! (dwb/update-branch-from-main branch)))})))

;; --- Branch info dialog (read-only info card + editable description)

(mf/defc branch-info-dialog*
  {::mf/register modal/components
   ::mf/register-as :branch-info}
  [{:keys [branch]}]
  (let [profile   (mf/deref refs/profile)
        profiles  (mf/deref refs/profiles)
        author    (or (get profiles (:created-by branch))
                      (when (= (:created-by branch) (:id profile)) profile))
        author?   (= (:created-by branch) (:id profile))
        status    (:status branch)
        merged?   (= "merged" status)
        archived? (contains? #{"archived" "merged"} status)

        editing?  (mf/use-state false)
        desc*     (mf/use-state (or (:description branch) ""))
        desc      (deref desc*)

        on-close       (mf/use-fn #(st/emit! (modal/hide)))
        on-edit        (mf/use-fn #(reset! editing? true))
        on-desc-change (mf/use-fn #(reset! desc* (dom/get-target-val %)))
        on-cancel      (mf/use-fn (mf/deps branch)
                                  (fn [_]
                                    (reset! desc* (or (:description branch) ""))
                                    (reset! editing? false)))
        on-save        (mf/use-fn (mf/deps branch desc)
                                  (fn [_]
                                    (st/emit! (dwb/set-branch-description (:id branch) (str/trim desc)))
                                    (reset! editing? false)))
        on-compare     (mf/use-fn (mf/deps branch)
                                  #(modal/show! :branch-compare {:branch branch}))]
    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container :info-container)}
      [:div {:class (stl/css :modal-header)}
       [:div {:class (stl/css :modal-header-icon)}
        [:> i/icon* {:icon-id i/git-branch}]]
       [:div {:class (stl/css :modal-header-text)}
        [:h2 {:class (stl/css :modal-title)} (tr "workspace.branches.info.title")]
        [:span {:class (stl/css :modal-subtitle)} (:name branch)]]
       [:span {:class (stl/css-case :info-status true
                                    :status-merged merged?
                                    :status-archived (= "archived" status)
                                    :status-open (= "open" status))}
        (tr (case status
              "merged"   "workspace.branches.info.status.merged"
              "archived" "workspace.branches.info.status.archived"
              "workspace.branches.info.status.open"))]
       [:> button* {:variant "ghost"
                    :icon i/close
                    :aria-label (tr "labels.close")
                    :on-click on-close}]]

      [:div {:class (stl/css :modal-content)}
       ;; description: shown read-only, editable inline by the author
       [:div {:class (stl/css :info-section)}
        [:div {:class (stl/css :info-section-head)}
         [:span {:class (stl/css :field-label)} (tr "workspace.branches.info.description")]
         (when (and author? (not (deref editing?)))
           [:> button* {:variant "ghost" :on-click on-edit} (tr "labels.edit")])]
        (if (deref editing?)
          [:div {:class (stl/css :info-desc-edit)}
           [:textarea {:class (stl/css :textarea)
                       :value desc
                       :on-change on-desc-change
                       :rows 3
                       :auto-focus true
                       :placeholder (tr "workspace.branches.info.description-placeholder")}]
           [:div {:class (stl/css :info-desc-actions)}
            [:> button* {:variant "secondary" :on-click on-cancel} (tr "labels.cancel")]
            [:> button* {:variant "primary" :on-click on-save} (tr "labels.save")]]]
          (if (str/blank? (:description branch))
            [:p {:class (stl/css :info-desc-empty)} (tr "workspace.branches.info.description-empty")]
            [:p {:class (stl/css :info-desc)} (:description branch)]))]

       ;; metadata
       [:dl {:class (stl/css :info-grid)}
        [:div {:class (stl/css :info-row)}
         [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.author")]
         [:dd {:class (stl/css :info-val)}
          (when author [:> avatar* {:profile author :variant "S"}])
          [:span (or (:fullname author) "—")]]]

        [:div {:class (stl/css :info-row)}
         [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.derived-from")]
         [:dd {:class (stl/css :info-val)}
          [:> i/icon* {:icon-id i/git-commit-vertical :size "s"}]
          [:span (or (:source-name branch) (tr "workspace.branches.main"))]]]

        (when (:created-at branch)
          [:div {:class (stl/css :info-row)}
           [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.created")]
           [:dd {:class (stl/css :info-val)}
            (-> (:created-at branch) ct/inst (ct/format-inst :localized-date-time))]])

        (when (:updated-at branch)
          [:div {:class (stl/css :info-row)}
           [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.updated")]
           [:dd {:class (stl/css :info-val)}
            (some-> (:updated-at branch) ct/inst ct/timeago)]])

        (when-not archived?
          [:div {:class (stl/css :info-row)}
           [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.changes")]
           [:dd {:class (stl/css :info-val :info-counts)}
            [:span {:class (stl/css :count-ahead)}
             [:> i/icon* {:icon-id i/arrow-up :size "s"}] (dm/str (:ahead branch 0))]
            [:span {:class (stl/css :count-behind)}
             [:> i/icon* {:icon-id i/arrow-down :size "s"}] (dm/str (:behind branch 0))]
            (when (pos? (:conflicts branch 0))
              [:span {:class (stl/css :item-badge :badge-conflict)}
               (tr "workspace.branches.banner.conflicts" (dm/str (:conflicts branch)))])]])

        (when (:merged-at branch)
          [:div {:class (stl/css :info-row)}
           [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.merged")]
           [:dd {:class (stl/css :info-val)}
            (-> (:merged-at branch) ct/inst (ct/format-inst :localized-date-time))]])]]

      [:div {:class (stl/css :modal-footer)}
       (when-not archived?
         [:> button* {:variant "secondary" :icon i/switch :on-click on-compare}
          (tr "workspace.branches.compare")])
       [:> button* {:variant "primary" :on-click on-close} (tr "labels.close")]]]]))

;; --- Branch list entry

(mf/defc branch-entry*
  {::mf/private true}
  [{:keys [entry profiles current pr menu-open? on-set-menu]}]
  (let [author    (get profiles (:created-by entry))
        ahead     (:ahead entry)
        behind    (:behind entry)
        main?     (:is-main entry)
        archived? (contains? #{"archived" "merged"} (:status entry))

        ;; `pr` is the branch's open pull request (nil when none): the
        ;; review is a STATE of the branch, so the entry surfaces it as a
        ;; badge and swaps "Request review" for the review actions
        profile    (mf/deref refs/profile)
        team       (mf/deref refs/team)
        author?    (and pr (= (:created-by pr) (:id profile)))
        admin?     (boolean (get-in team [:permissions :is-admin]))
        can-merge? (boolean (get-in team [:permissions :can-edit]))

        [approvals total] (if pr (prui/current-approvals pr) [0 0])

        editing?   (mf/use-state false)

        on-open
        (mf/use-fn
         (mf/deps entry editing?)
         (fn [_]
           (when-not (deref editing?)
             (st/emit! (dwb/open-branch (:branch-file-id entry))))))

        on-compare
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (modal/show! :branch-compare {:branch entry})))

        on-update
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (confirm-update! entry)))

        on-open-menu
        (mf/use-fn (mf/deps on-set-menu)
                   (fn [event]
                     (dom/stop-propagation event)
                     (on-set-menu true)))
        on-close-menu (mf/use-fn (mf/deps on-set-menu) #(on-set-menu false))

        on-info
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (modal/show! :branch-info {:branch entry})))

        on-start-rename
        (mf/use-fn (fn [event]
                     (dom/stop-propagation event)
                     (on-set-menu false)
                     (reset! editing? true)))

        on-request-review
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (modal/show! :create-pull-request {:branch entry})))

        on-open-review
        (mf/use-fn
         (mf/deps pr)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (st/emit! (dwpr/open-pull-request pr))))

        on-review-details
        (mf/use-fn
         (mf/deps pr)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (modal/show! :pull-request-info {:pr pr})))

        on-publish-review
        (mf/use-fn
         (mf/deps pr)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (st/emit! (dwpr/update-pull-request-snapshot (:id pr)))))

        on-merge-review
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (confirm-merge! entry)))

        on-cancel-review
        (mf/use-fn
         (mf/deps pr)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (prui/confirm-close! pr)))

        on-rename-commit
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (let [value (str/trim (dom/get-target-val event))]
             (when (and (seq value) (not= value (:name entry)))
               (st/emit! (dwb/rename-branch (:id entry) value)))
             (reset! editing? false))))

        on-rename-key-down
        (mf/use-fn
         (mf/deps on-rename-commit)
         (fn [event]
           (cond
             (kbd/enter? event) (on-rename-commit event)
             (kbd/esc? event)   (reset! editing? false))))

        on-archive
        (mf/use-fn (mf/deps entry archived?)
                   (fn [event]
                     (dom/stop-propagation event)
                     (on-set-menu false)
                     (st/emit! (dwb/archive-branch (:id entry) (not archived?)))))

        on-delete
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (st/emit! (modal/show {:type :confirm
                                  :title (tr "workspace.branches.delete.title")
                                  :message (tr "workspace.branches.delete.message" (:name entry))
                                  :accept-label (tr "labels.delete")
                                  :accept-style :danger
                                  :on-accept (fn [_] (st/emit! (dwb/delete-branch (:id entry))))}))))]

    [:li {:class (stl/css-case :branch-entry true
                               :is-archived archived?
                               :is-current current
                               :is-menu-open menu-open?)
          :role "button"
          :on-click (when-not current on-open)}
     [:div {:class (stl/css :branch-entry-icon)}
      [:> i/icon* {:icon-id (if main? i/git-commit-vertical i/git-branch)}]]

     [:div {:class (stl/css :branch-entry-body)}
      (if (deref editing?)
        [:input {:class (stl/css :branch-rename-input)
                 :default-value (:name entry)
                 :auto-focus true
                 :on-click (fn [e] (dom/stop-propagation e))
                 :on-blur on-rename-commit
                 :on-key-down on-rename-key-down}]
        [:span {:class (stl/css :branch-entry-name)} (:name entry)])
      [:div {:class (stl/css :branch-entry-meta)}
       ;; Main is the default branch: always show it the same way (no avatar,
       ;; just the "Default branch" subtitle), even when it's the current branch
       (when (and author (not main?))
         [:> avatar* {:profile author :variant "S"}])
       [:span {:class (stl/css :branch-entry-author)}
        (if main?
          (tr "workspace.branches.main-subtitle")
          (:fullname author))]]
      (when (some? pr)
        [:div {:class (stl/css :branch-entry-badges)}
         [:> i/icon* {:icon-id i/git-pull-request-arrow :size "s"}]
         [:> pr-state-badge* {:pr pr}]
         (when (pos? total)
           [:span {:class (stl/css :branch-pr-approvals)
                   :title (tr "workspace.pull-requests.info.approvals")}
            [:> i/icon* {:icon-id i/tick :size "s"}]
            (dm/str approvals "/" total)])])]

     [:div {:class (stl/css :branch-entry-aside)}
      (when (and (not archived?) (not main?))
        [:div {:class (stl/css :branch-entry-counts)}
         [:span {:class (stl/css :count-ahead)}
          [:> i/icon* {:icon-id i/arrow-up :size "s"}]
          (dm/str ahead)]
         [:span {:class (stl/css :count-behind)}
          [:> i/icon* {:icon-id i/arrow-down :size "s"}]
          (dm/str behind)]])

      (when-not main?
        [:> icon-button* {:variant "ghost"
                          :icon i/menu
                          :aria-label (tr "labels.options")
                          :on-click on-open-menu}])

      [:> dropdown-menu* {:show (and (not main?) menu-open?)
                          :on-close on-close-menu
                          :class (stl/css :branch-options-dropdown)}
       [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-info}
        (tr "workspace.branches.menu.info")]
       (when-not archived?
         [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-compare}
          (tr "workspace.branches.compare")])
       (when (and (not archived?) (pos? behind))
         [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-update}
          (tr "workspace.branches.update")])
       ;; review lifecycle: a branch without an open pull request offers to
       ;; request one; with it, the review actions take its place (the
       ;; review either ends merging the branch or is cancelled, leaving
       ;; the branch as a normal branch again)
       (when (and (not archived?) (dwpr/enabled?) (nil? pr))
         [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-request-review}
          (tr "workspace.pull-requests.menu.request-review")])
       (when (some? pr)
         [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-open-review}
          (tr "workspace.pull-requests.actions.open-review")])
       (when (some? pr)
         [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-review-details}
          (tr "workspace.pull-requests.menu.review-details")])
       (when (and (some? pr) author?)
         [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-publish-review}
          (tr "workspace.pull-requests.menu.publish")])
       (when (and (some? pr) can-merge?)
         [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-merge-review}
          (tr "workspace.pull-requests.actions.merge")])
       (when (and (some? pr) (or author? admin?))
         [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-cancel-review}
          (tr "workspace.pull-requests.actions.close")])
       (when-not archived?
         [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-start-rename}
          (tr "labels.rename")])
       [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-archive}
        (tr (if archived?
              "workspace.branches.menu.restore"
              "workspace.branches.menu.archive"))]
       [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-delete}
        (tr "labels.delete")]]]]))

;; --- Branches panel

(mf/defc branches-toolbox*
  []
  (let [profiles   (mf/deref refs/profiles)
        profile    (mf/deref refs/profile)
        file       (mf/deref refs/file)
        branch-ctx (mf/deref branch-context)

        {:keys [status data] :as _state}
        (mf/deref branches)

        ;; a pull request is a state OF its branch: index the open ones by
        ;; branch id so each entry can surface its review state inline
        prs-by-branch
        (when (dwpr/enabled?)
          (->> (:data (mf/deref refs/pull-requests))
               (filter #(= "open" (:status %)))
               (map (juxt :file-branch-id identity))
               (into {})))

        ;; make sure the current user resolves as an author (e.g. for the
        ;; synthetic Main entry and branches the user created)
        profiles  (cond-> profiles
                    (:id profile) (assoc (:id profile) profile))

        ;; the branch (or main) the open file currently is
        current-entry
        (if branch-ctx
          branch-ctx
          {:name (tr "workspace.branches.main")
           :created-by (:id profile)
           :is-main true
           :status "open"
           :branch-file-id (:id file)})

        filter*  (mf/use-state "")
        filter-v (deref filter*)

        show-archived? (mf/use-state false)

        ;; only one entry's options dropdown may be open at a time; the open
        ;; entry's key lives here (in the parent) so opening one closes any
        ;; other. nil = none open.
        open-menu*  (mf/use-state nil)
        open-menu   (deref open-menu*)
        entry-key   (fn [entry] (dm/str (or (:id entry) (:branch-file-id entry))))
        set-menu    (mf/use-fn (fn [k open?] (reset! open-menu* (when open? k))))

        entries
        (mf/with-memo [data filter-v]
          (->> data
               (filter #(or (str/blank? filter-v)
                            (str/includes? (str/lower (or (:name %) ""))
                                           (str/lower filter-v))))))

        ;; the current branch (if any) is shown in its own section; drop it
        ;; from the rest of the list
        open-entries     (->> entries
                              (filter #(= "open" (:status %)))
                              (remove #(= (:id %) (:id branch-ctx))))
        archived-entries (->> entries
                              (remove #(= "open" (:status %)))
                              (remove #(= (:id %) (:id branch-ctx))))

        ;; when the open file is a branch, offer Main as a switch target
        main-entry       (when branch-ctx
                           {:name (tr "workspace.branches.main")
                            :is-main true
                            :status "open"
                            :branch-file-id (:source-file-id branch-ctx)})

        other-entries    (cond->> open-entries
                           main-entry (cons main-entry))

        on-filter-change
        (mf/use-fn #(reset! filter* (dom/get-target-val %)))

        on-toggle-archived
        (mf/use-fn #(swap! show-archived? not))

        on-create
        (mf/use-fn
         (mf/deps file)
         (fn [_]
           (modal/show! :create-branch {:file-name (:name file)})))]

    (mf/with-effect []
      (st/emit! (dwb/init-branches-state))
      (when (dwpr/enabled?)
        (st/emit! (dwpr/init-pull-requests-state))))

    [:div {:class (stl/css :branches-toolbox)}
     [:div {:class (stl/css :branches-header)}
      [:> input* {:variant "dense"
                  :icon i/search
                  :placeholder (tr "workspace.branches.search.placeholder")
                  :value filter-v
                  :on-change on-filter-change}]
      [:> button* {:variant "primary"
                   :icon i/git-branch-plus
                   :on-click on-create}
       (tr "workspace.branches.new")]]

     (cond
       (= status :loading)
       [:div {:class (stl/css :branches-empty)}
        [:> empty-state* {:icon i/git-branch
                          :text (tr "workspace.branches.loading")}]]

       :else
       [:div {:class (stl/css :branches-list)}
        ;; current branch (or main) the open file currently is
        [:div {:class (stl/css :branches-section-header)}
         [:span (tr "workspace.branches.section.current")]]
        [:ul {:class (stl/css :branches-entries)}
         (let [k (entry-key current-entry)]
           [:> branch-entry* {:entry current-entry
                              :profiles profiles
                              :current true
                              :pr (get prs-by-branch (:id current-entry))
                              :menu-open? (= open-menu k)
                              :on-set-menu (partial set-menu k)}])]

        (when (seq other-entries)
          [:div {:class (stl/css :branches-section-header)}
           [:span (tr "workspace.branches.section.others")]
           [:span {:class (stl/css :branches-section-count)} (dm/str (count other-entries))]])
        (when (seq other-entries)
          [:ul {:class (stl/css :branches-entries)}
           (for [entry other-entries]
             (let [k (entry-key entry)]
               [:> branch-entry* {:key k
                                  :entry entry
                                  :profiles profiles
                                  :pr (get prs-by-branch (:id entry))
                                  :menu-open? (= open-menu k)
                                  :on-set-menu (partial set-menu k)}]))])

        (when (seq archived-entries)
          [:div {:class (stl/css :branches-archived)}
           [:button {:class (stl/css :branches-archived-toggle)
                     :on-click on-toggle-archived}
            [:> i/icon* {:icon-id (if (deref show-archived?) i/arrow-down i/arrow-right) :size "s"}]
            [:span (tr "workspace.branches.archived")]
            [:span {:class (stl/css :branches-archived-count)} (dm/str (count archived-entries))]]
           (when (deref show-archived?)
             [:ul {:class (stl/css :branches-entries)}
              (for [entry archived-entries]
                (let [k (entry-key entry)]
                  [:> branch-entry* {:key k
                                     :entry entry
                                     :profiles profiles
                                     :menu-open? (= open-menu k)
                                     :on-set-menu (partial set-menu k)}]))])])])]))

;; --- Compare changes dialog (read-only 3-way diff)

(mf/defc compare-value-chip*
  {::mf/private true}
  [{:keys [value tone]}]
  (let [hex (hex-color value)]
    [:span {:class (stl/css-case :value-chip true
                                 :value-main (= tone :main)
                                 :value-branch (= tone :branch)
                                 :value-base (= tone :base))}
     (when hex
       [:span {:class (stl/css :value-swatch)
               :style {:background-color hex}}])
     (if (and hex (not (sequential? value)))
       hex
       (display-val value))]))

(mf/defc branch-compare-item*
  {::mf/private true}
  [{:keys [item index selected on-select]}]
  (let [on-click (mf/use-fn (mf/deps index on-select) #(on-select index))
        status   (item-status item)
        attrs    (:changed-attrs item)
        type-lbl (type-label-key item)
        icon-id  (if (= :shape (:kind item))
                   (shape-icon-id item)
                   (get kind->icon (:kind item) i/git-branch))
        subtitle (cond
                   (= :added status)   (tr "workspace.branches.compare.subtitle-new"
                                           (if type-lbl (tr type-lbl) ""))
                   (and (= :modified status) (seq attrs))
                   (tr "workspace.branches.compare.subtitle-mods" (count attrs))
                   type-lbl (tr type-lbl)
                   :else nil)]
    [:li {:class (stl/css-case :compare-item true
                               :is-selected (= index selected)
                               :status-added    (= :added status)
                               :status-modified (= :modified status)
                               :status-deleted  (= :deleted status)
                               :status-conflict (= :conflict status))
          :role "button"
          :on-click on-click}
     [:div {:class (stl/css :compare-item-icon)}
      [:> i/icon* {:icon-id icon-id}]]
     [:div {:class (stl/css :compare-item-body)}
      [:span {:class (stl/css :compare-item-label)} (:label item)]
      (when subtitle
        [:span {:class (stl/css :compare-item-subtitle)} subtitle])]
     [:span {:class (stl/css-case :item-badge true
                                  :badge-added    (= :added status)
                                  :badge-modified (= :modified status)
                                  :badge-deleted  (= :deleted status)
                                  :badge-conflict (= :conflict status))}
      (tr (get status->label status "workspace.branches.status.modified"))]]))

(mf/defc branch-compare-detail*
  {::mf/private true}
  [{:keys [item]}]
  (let [attrs    (:changed-attrs item)
        type-lbl (type-label-key item)]
    (cond
      (nil? item)
      [:div {:class (stl/css :compare-detail-empty)}
       [:> empty-state* {:icon i/switch
                         :text (tr "workspace.branches.compare.select-hint")}]]

      :else
      [:div {:class (stl/css :compare-detail-content)}
       [:div {:class (stl/css :compare-detail-head)}
        [:h3 {:class (stl/css :compare-detail-title)} (:label item)]
        (when type-lbl
          [:span {:class (stl/css :compare-detail-type)} (tr type-lbl)])]

       (if (seq attrs)
         [:div {:class (stl/css :prop-changes)}
          [:div {:class (stl/css :prop-changes-head)}
           [:span (tr "workspace.branches.compare.prop-changes")]
           [:span {:class (stl/css :prop-changes-count)} (dm/str (count attrs))]]
          (for [[attr {:keys [main branch]}] attrs]
            [:div {:class (stl/css :prop-row) :key (str attr)}
             [:span {:class (stl/css :prop-name)} (attr-label attr)]
             [:div {:class (stl/css :prop-values)}
              [:> compare-value-chip* {:value main :tone :main}]
              [:> i/icon* {:icon-id i/arrow-long-right :size "s"}]
              [:> compare-value-chip* {:value branch :tone :branch}]]])]
         [:p {:class (stl/css :compare-detail-hint)}
          (tr "workspace.branches.compare.no-props")])])))

(mf/defc branch-compare-dialog*
  {::mf/register modal/components
   ::mf/register-as :branch-compare}
  [{:keys [branch]}]
  (let [{:keys [status diff selected direction]} (mf/deref branch-diff)
        direction  (or direction :branch->main)
        incoming?  (= direction :main->branch)

        items
        (mf/with-memo [diff]
          (into (vec (:changes diff)) (:conflicts diff)))

        stats    (:stats diff)
        sel-item (when (and (some? selected) (< selected (count items)))
                   (nth items selected))

        active-filter* (mf/use-state :all)
        active-filter  (deref active-filter*)

        ;; keep the original index for selection while filtering/grouping
        indexed   (map-indexed vector items)
        filtered  (filterv (fn [[_ it]] (status-matches? active-filter (item-status it))) indexed)
        by-cat    (group-by (fn [[_ it]] (get kind->category (:kind it) :pages)) filtered)

        on-close   (mf/use-fn #(st/emit! (modal/hide)))
        on-select  (mf/use-fn #(st/emit! (dwb/select-diff-change %)))
        on-filter  (mf/use-fn (fn [f] (reset! active-filter* f)))
        on-swap    (mf/use-fn (mf/deps branch direction)
                              #(st/emit! (dwb/fetch-branch-diff
                                          (:id branch)
                                          (if incoming? :branch->main :main->branch))))
        on-merge   (mf/use-fn (mf/deps branch)
                              #(confirm-merge! branch))
        on-update  (mf/use-fn (mf/deps branch)
                              #(confirm-update! branch))
        on-resolve (mf/use-fn (mf/deps branch direction)
                              #(modal/show! :branch-conflicts
                                            {:branch branch
                                             :mode (if incoming? :update :merge)}))
        on-export  (mf/use-fn
                    (mf/deps diff branch)
                    (fn []
                      (let [payload (clj->js {:stats stats
                                              :changes (:changes diff)
                                              :conflicts (:conflicts diff)})
                            text    (js/JSON.stringify payload nil 2)
                            blob    (js/Blob. #js [text] #js {:type "application/json"})
                            url     (js/URL.createObjectURL blob)
                            a       (.createElement js/document "a")]
                        (set! (.-href a) url)
                        (set! (.-download a) (dm/str (:name branch) "-diff.json"))
                        (.click a)
                        (js/URL.revokeObjectURL url))))]

    (mf/with-effect [(:id branch)]
      (st/emit! (dwb/fetch-branch-diff (:id branch))))

    ;; auto-select the first change once loaded
    (mf/with-effect [status (count items)]
      (when (and (= status :loaded) (nil? selected) (seq items))
        (st/emit! (dwb/select-diff-change 0))))

    [:div {:class (stl/css :compare-overlay)}
     [:div {:class (stl/css :compare-container)}
      [:div {:class (stl/css :compare-header)}
       [:div {:class (stl/css :compare-title-group)}
        [:div {:class (stl/css :compare-title-icon)}
         [:> i/icon* {:icon-id i/switch}]]
        [:div {:class (stl/css :compare-title-text)}
         [:h2 {:class (stl/css :modal-title)} (tr "workspace.branches.compare.title")]
         [:div {:class (stl/css :compare-breadcrumb)}
          (if incoming?
            [:*
             [:span {:class (stl/css :breadcrumb-main)}
              [:> i/icon* {:icon-id i/git-commit-vertical :size "s"}]
              (tr "workspace.branches.main")]
             [:button {:class (stl/css :breadcrumb-swap)
                       :title (tr "workspace.branches.compare.swap")
                       :aria-label (tr "workspace.branches.compare.swap")
                       :on-click on-swap}
              [:> i/icon* {:icon-id i/arrow-long-right :size "s"}]]
             [:span {:class (stl/css :breadcrumb-branch)}
              [:> i/icon* {:icon-id i/git-branch :size "s"}]
              (:name branch)]]
            [:*
             [:span {:class (stl/css :breadcrumb-branch)}
              [:> i/icon* {:icon-id i/git-branch :size "s"}]
              (:name branch)]
             [:button {:class (stl/css :breadcrumb-swap)
                       :title (tr "workspace.branches.compare.swap")
                       :aria-label (tr "workspace.branches.compare.swap")
                       :on-click on-swap}
              [:> i/icon* {:icon-id i/arrow-long-right :size "s"}]]
             [:span {:class (stl/css :breadcrumb-main)}
              [:> i/icon* {:icon-id i/git-commit-vertical :size "s"}]
              (tr "workspace.branches.main")]])]]]

       (when stats
         [:div {:class (stl/css :compare-stats)}
          [:span {:class (stl/css :stat-badge :badge-added)}
           (dm/str (:added stats) " " (tr "workspace.branches.compare.stats.added"))]
          [:span {:class (stl/css :stat-badge :badge-modified)}
           (dm/str (:modified stats) " " (tr "workspace.branches.compare.stats.modified"))]
          [:span {:class (stl/css :stat-badge :badge-deleted)}
           (dm/str (:deleted stats) " " (tr "workspace.branches.compare.stats.deleted"))]
          (when (pos? (:conflicts stats))
            [:span {:class (stl/css :stat-badge :badge-conflict)}
             (dm/str (:conflicts stats) " " (tr "workspace.branches.compare.stats.conflicts"))])])

       [:> icon-button* {:variant "ghost"
                         :icon i/close
                         :aria-label (tr "labels.close")
                         :on-click on-close}]]

      (cond
        (= status :loading)
        [:div {:class (stl/css :compare-empty)}
         [:> empty-state* {:icon i/switch
                           :text (tr "workspace.branches.compare.loading")}]]

        (= status :error)
        [:div {:class (stl/css :compare-empty)}
         [:> empty-state* {:icon i/triangle-alert
                           :text (tr "workspace.branches.compare.error")}]]

        (empty? items)
        [:div {:class (stl/css :compare-empty)}
         [:> empty-state* {:icon i/git-branch
                           :text (tr "workspace.branches.compare.empty")}]]

        :else
        [:div {:class (stl/css :compare-body)}
         [:div {:class (stl/css :compare-side)}
          [:div {:class (stl/css :compare-filters)}
           (for [[f label] compare-filters]
             [:button {:key (name f)
                       :class (stl/css-case :filter-chip true
                                            :is-active (= active-filter f)
                                            :dot-added    (= f :added)
                                            :dot-modified (= f :modified)
                                            :dot-deleted  (= f :deleted))
                       :on-click #(on-filter f)}
              (when-not (= f :all) [:span {:class (stl/css :filter-dot)}])
              (tr label)])]

          [:div {:class (stl/css :compare-list)}
           (for [cat category-order
                 :let [group (get by-cat cat)]
                 :when (seq group)]
             [:div {:class (stl/css :compare-group) :key (name cat)}
              [:div {:class (stl/css :compare-group-head)}
               [:> i/icon* {:icon-id (get category->icon cat i/document) :size "s"}]
               [:span {:class (stl/css :compare-group-label)} (tr (get category->label cat))]
               (let [freqs (frequencies (map (fn [[_ it]] (item-status it)) group))]
                 [:span {:class (stl/css :compare-group-counts)}
                  (when (pos? (get freqs :added 0))
                    [:span {:class (stl/css :count-added)} (dm/str "+" (get freqs :added))])
                  (when (pos? (+ (get freqs :modified 0) (get freqs :conflict 0)))
                    [:span {:class (stl/css :count-modified)}
                     (dm/str "~" (+ (get freqs :modified 0) (get freqs :conflict 0)))])
                  (when (pos? (get freqs :deleted 0))
                    [:span {:class (stl/css :count-deleted)} (dm/str "−" (get freqs :deleted))])])]
              [:ul {:class (stl/css :compare-group-items)}
               (for [[idx item] group]
                 [:> branch-compare-item* {:key idx
                                           :item item
                                           :index idx
                                           :selected selected
                                           :on-select on-select}])]])]]

         [:div {:class (stl/css :compare-detail)}
          [:> branch-compare-detail* {:item sel-item}]]])

      (when (= status :loaded)
        (let [conflicts  (long (or (:conflicts stats) 0))
              conflicts? (pos? conflicts)
              total      (long (+ (or (:added stats) 0)
                                  (or (:modified stats) 0)
                                  (or (:deleted stats) 0)))]
          [:div {:class (stl/css :compare-footer)}
           [:div {:class (stl/css :compare-footer-info)}
            [:> i/icon* {:icon-id i/info :size "s"}]
            [:span (tr "workspace.branches.compare.footer-changes" total)]
            (when conflicts?
              [:span {:class (stl/css :compare-footer-conflicts)}
               (dm/str " · " (tr "workspace.branches.compare.footer-conflicts" conflicts))])]
           [:div {:class (stl/css :compare-footer-actions)}
            [:> button* {:variant "secondary"
                         :icon i/download
                         :on-click on-export}
             (tr "workspace.branches.compare.export")]
            ;; one action that follows the current direction: update from main
            ;; while viewing main's incoming changes, merge otherwise
            (cond
              conflicts?
              [:> button* {:variant "primary"
                           :icon i/git-merge
                           :on-click on-resolve}
               (tr "workspace.branches.conflicts.resolve")]

              incoming?
              [:> button* {:variant "primary"
                           :icon i/status-update
                           :disabled (zero? total)
                           :on-click on-update}
               (tr "workspace.branches.update")]

              :else
              [:> button* {:variant "primary"
                           :icon i/git-merge
                           :disabled (zero? total)
                           :on-click on-merge}
               (tr "workspace.branches.merge.action")])]]))]]))

;; --- Resolve conflicts dialog

(def ^:private conflict-reason->label
  {:modify-modify "workspace.branches.conflicts.reason.modify-modify"
   :add-add       "workspace.branches.conflicts.reason.add-add"
   :delete-modify "workspace.branches.conflicts.reason.delete-modify"
   :modify-delete "workspace.branches.conflicts.reason.modify-delete"})

(mf/defc branch-conflict-item*
  {::mf/private true}
  [{:keys [conflict index selected resolution on-select]}]
  (let [on-click  (mf/use-fn (mf/deps index on-select) #(on-select index))
        resolved? (bm/conflict-resolved? conflict resolution)
        mixed?    (map? resolution)
        icon-id   (if (= :shape (:kind conflict))
                    (shape-icon-id conflict)
                    (get kind->icon (:kind conflict) i/git-branch))
        type-lbl  (type-label-key conflict)]
    [:li {:class (stl/css-case :conflict-item true
                               :is-selected (= index selected)
                               :is-pending (not resolved?))
          :role "button"
          :on-click on-click}
     [:div {:class (stl/css :conflict-item-icon)}
      [:> i/icon* {:icon-id icon-id}]]
     [:div {:class (stl/css :conflict-item-body)}
      [:span {:class (stl/css :conflict-item-label)} (:label conflict)]
      [:span {:class (stl/css :conflict-item-subtitle)}
       (cond-> ""
         type-lbl (str (tr type-lbl) " · ")
         :always  (str (tr (if resolved?
                             "workspace.branches.conflicts.state-resolved"
                             "workspace.branches.conflicts.state-pending"))))]]
     (cond
       (not resolved?)
       [:span {:class (stl/css :conflict-pill :pill-pending)}
        [:> i/icon* {:icon-id i/triangle-alert :size "s"}]]
       mixed?
       [:span {:class (stl/css :conflict-pill :pill-mixed)}
        [:span {:class (stl/css :pill-dot)}]
        (tr "workspace.branches.conflicts.side-mixed")]
       (= resolution :main)
       [:span {:class (stl/css :conflict-pill :pill-main)}
        [:span {:class (stl/css :pill-dot)}]
        (tr "workspace.branches.conflicts.side-main")]
       :else
       [:span {:class (stl/css :conflict-pill :pill-branch)}
        [:span {:class (stl/css :pill-dot)}]
        (tr "workspace.branches.conflicts.side-branch")])]))

;; --- Per-element visual previews ---

(def ^:private renderable-shape-types
  "Shape types we can faithfully render to SVG from a standalone shape map.
  Images / svg-raw reference (possibly cross-file) media and fall back to a
  type icon instead."
  #{:rect :circle :frame :path :bool :text :group})

(def ^:private geometry-attrs
  "Attrs whose change invalidates the cached `:selrect`/`:points`; when any
  of them is taken from the branch in a per-attr resolution, the result
  preview must adopt the branch's geometry so the framing stays correct."
  #{:x :y :width :height :rotation :transform :transform-inverse :flip-x :flip-y})

(defn- shape-bounds
  "Axis-aligned bounds framing a shape, taken from its rotated corner
  `:points` when present, else its `:selrect`."
  [shape]
  (let [pts (:points shape)]
    (if (and (sequential? pts) (seq pts))
      (let [xs (keep :x pts) ys (keep :y pts)
            x (apply min xs) y (apply min ys)]
        {:x x :y y :width (- (apply max xs) x) :height (- (apply max ys) y)})
      (let [sr (:selrect shape)]
        {:x (:x sr 0) :y (:y sr 0) :width (:width sr 1) :height (:height sr 1)}))))

(mf/defc shape-preview*
  "Renders a single shape map to a small, fitted SVG using the real shape
  renderers (so fills, gradients, strokes, corner radii, opacity and text
  are accurate), without needing any workspace/objects context."
  {::mf/private true}
  [{:keys [shape]}]
  (let [{:keys [x y width height]} (shape-bounds shape)
        width   (max 1 width)
        height  (max 1 height)
        objects (mf/with-memo [shape] {(:id shape) shape})
        wrapper (mf/with-memo [objects] (render/shape-wrapper-factory objects))]
    [:svg {:class (stl/css :preview-svg)
           :viewBox (dm/str x " " y " " width " " height)
           :preserveAspectRatio "xMidYMid meet"
           :xmlns "http://www.w3.org/2000/svg"
           :fill "none"}
     [:> wrapper {:shape shape}]]))

(mf/defc entity-preview*
  "Visual preview of one side of a conflict, dispatching on entity kind:
  shapes render to SVG, library colors to a swatch, typographies to a text
  sample; everything else (and a nil/deleted side) shows a placeholder."
  {::mf/private true}
  [{:keys [kind value]}]
  (cond
    (nil? value)
    [:div {:class (stl/css :preview-empty)}
     [:> i/icon* {:icon-id i/delete :size "s"}]]

    (= kind :shape)
    (if (contains? renderable-shape-types (:type value))
      [:> shape-preview* {:shape value}]
      [:div {:class (stl/css :preview-empty)}
       [:> i/icon* {:icon-id (shape-icon-id value)}]])

    (= kind :color)
    [:div {:class (stl/css :preview-swatch)
           :style {:background (or (uc/color->background value)
                                   (hex-color value)
                                   "transparent")}}]

    (= kind :typography)
    [:div {:class (stl/css :preview-type-sample)
           :style {:font-family (:font-family value)
                   :font-weight (:font-weight value)
                   :font-style (:font-style value)}}
     "Ag"]

    :else
    [:div {:class (stl/css :preview-empty)}
     [:> i/icon* {:icon-id (get kind->icon kind i/git-branch)}]]))

(defn- merged-entity
  "Synthesize the resulting entity for the RESULT preview from the `main`
  and `branch` entities, the conflict's changed-attr keys and the current
  `res` (a `:main`/`:branch` keyword or a per-attr `{attr -> side}` map)."
  [main branch attrs res]
  (cond
    (= res :branch) branch
    (or (nil? res) (= res :main)) main
    (map? res)
    (let [merged (reduce (fn [acc k]
                           (if (= :branch (get res k))
                             (assoc acc k (get branch k))
                             acc))
                         main attrs)]
      (if (and (map? main) (map? branch)
               (some (fn [k] (and (contains? geometry-attrs k) (= :branch (get res k)))) attrs))
        (assoc merged
               :selrect (:selrect branch)
               :points (:points branch)
               :transform (:transform branch)
               :transform-inverse (:transform-inverse branch))
        merged))
    :else main))

(defn- attr-choice
  "Currently-selected side (`:main`/`:branch`) for `attr` under resolution
  `res`, or nil when undecided (so the property row shows no selection until
  the user acts)."
  [res attr]
  (cond
    (= res :branch) :branch
    (= res :main)   :main
    (map? res)      (get res attr)
    :else           nil))

(mf/defc conflict-preview-row*
  "The BASE · MAIN · BRANCH · RESULT preview strip on top of the detail
  panel. RESULT updates live as per-property choices change."
  {::mf/private true}
  [{:keys [conflict resolution base-sub main-sub branch-sub]}]
  (let [kind   (:kind conflict)
        attrs  (keys (:changed-attrs conflict))
        result (merged-entity (:main conflict) (:branch conflict) attrs resolution)
        cols   [[:base   "workspace.branches.conflicts.card.base"   base-sub   (:base conflict)]
                [:main   "workspace.branches.conflicts.card.main"   main-sub   (:main conflict)]
                [:branch "workspace.branches.conflicts.card.branch" branch-sub (:branch conflict)]
                [:result "workspace.branches.conflicts.card.result" nil        result]]]
    [:div {:class (stl/css :preview-row)}
     (for [[col-key label sub value] cols]
       [:div {:class (stl/css-case :preview-col true :is-result (= col-key :result))
              :key (name col-key)}
        [:span {:class (stl/css :preview-col-label)} (tr label)]
        [:div {:class (stl/css :preview-box)}
         [:> entity-preview* {:kind kind :value value}]]
        (when sub [:span {:class (stl/css :preview-col-sub)} sub])])]))

(mf/defc conflict-prop-row*
  "One property of a conflict: its label and two clickable value chips
  (MAIN / BRANCH). Clicking a chip keeps that side for this property only."
  {::mf/private true}
  [{:keys [id attr main branch choice attrs]}]
  (let [on-main   (mf/use-fn (mf/deps id attr attrs)
                             #(st/emit! (dwb/set-conflict-attr-resolution id attr :main attrs)))
        on-branch (mf/use-fn (mf/deps id attr attrs)
                             #(st/emit! (dwb/set-conflict-attr-resolution id attr :branch attrs)))]
    [:div {:class (stl/css :prop-pick-row)}
     [:span {:class (stl/css :prop-pick-name)} (attr-label attr)]
     [:button {:class (stl/css-case :prop-pick true :is-chosen (= choice :main))
               :on-click on-main}
      [:> compare-value-chip* {:value main :tone :main}]]
     [:button {:class (stl/css-case :prop-pick true :is-chosen (= choice :branch))
               :on-click on-branch}
      [:> compare-value-chip* {:value branch :tone :branch}]]]))

(mf/defc branch-conflicts-dialog*
  {::mf/register modal/components
   ::mf/register-as :branch-conflicts}
  [{:keys [branch mode]}]
  (let [{:keys [diff selected resolutions status]} (mf/deref branch-diff)

        loading?    (= status :loading)

        ;; merge mode only: whether to keep the branch archived after merge
        keep?*      (mf/use-state false)
        keep?       (deref keep?*)
        on-toggle-keep (mf/use-fn #(swap! keep?* not))

        diff-meta   (:meta diff)
        ;; per-side header subtitle: base is pinned at branch creation, main
        ;; and branch show how recent each one is ("3 hours ago"); the branch
        ;; also names the author as "you" (it's always the current user).
        base-sub    (tr "workspace.branches.conflicts.card.base-meta")
        main-sub    (some-> (:main-at diff-meta) ct/inst ct/timeago)
        branch-sub  (let [ago (some-> (:branch-at diff-meta) ct/inst ct/timeago)
                          you (tr "workspace.branches.conflicts.card.you")]
                      (if ago (str ago " · " you) you))

        conflicts   (:conflicts diff)
        resolutions (or resolutions {})
        total       (count conflicts)
        resolved    (count (filterv #(bm/conflict-resolved? % (get resolutions (:id %))) conflicts))
        pending     (- total resolved)
        all-done?   (and (pos? total) (zero? pending))

        sel-idx     (min (or selected 0) (max 0 (dec total)))
        sel         (when (seq conflicts) (nth conflicts sel-idx))
        sel-res     (when sel (get resolutions (:id sel)))
        sel-type    (when sel (type-label-key sel))
        sel-reason  (when sel (get conflict-reason->label (:reason sel)))
        sel-icon    (when sel (if (= :shape (:kind sel))
                                (shape-icon-id sel)
                                (get kind->icon (:kind sel) i/git-branch)))

        on-close      (mf/use-fn #(st/emit! (modal/hide)))
        on-select     (mf/use-fn #(st/emit! (dwb/select-diff-change %)))
        on-all-main   (mf/use-fn #(st/emit! (dwb/set-all-resolutions :main)))
        on-all-branch (mf/use-fn #(st/emit! (dwb/set-all-resolutions :branch)))
        on-use-main   (mf/use-fn (mf/deps sel)
                                 #(when sel (st/emit! (dwb/set-conflict-resolution (:id sel) :main))))
        on-use-branch (mf/use-fn (mf/deps sel)
                                 #(when sel (st/emit! (dwb/set-conflict-resolution (:id sel) :branch))))
        on-apply      (mf/use-fn (mf/deps branch resolutions mode keep?)
                                 #(st/emit! (if (= mode :update)
                                              (dwb/update-branch-from-main branch resolutions)
                                              (dwb/merge-branch branch {:resolutions resolutions
                                                                        :keep-branch keep?}))))]

    (mf/with-effect [(:id branch)]
      (st/emit! (dwb/fetch-branch-diff (:id branch))))

    [:div {:class (stl/css :compare-overlay)}
     [:div {:class (stl/css :compare-container)}
      [:div {:class (stl/css :compare-header)}
       [:div {:class (stl/css :compare-title-group)}
        [:div {:class (stl/css :compare-title-icon :title-icon-warning)}
         [:> i/icon* {:icon-id i/triangle-alert}]]
        [:div {:class (stl/css :compare-title-text)}
         [:h2 {:class (stl/css :modal-title)} (tr "workspace.branches.conflicts.title")]
         [:span {:class (stl/css :compare-subtitle)}
          (tr "workspace.branches.conflicts.subtitle" (:name branch))]]]
       [:span {:class (stl/css :conflicts-progress)}
        (tr "workspace.branches.conflicts.progress" (str resolved) (str total))]
       [:> icon-button* {:variant "ghost"
                         :icon i/close
                         :aria-label (tr "labels.close")
                         :on-click on-close}]]

      [:div {:class (stl/css :conflicts-toolbar)}
       [:span {:class (stl/css :bulk-label)} (tr "workspace.branches.conflicts.bulk-label")]
       [:> button* {:variant "secondary" :icon i/git-branch :on-click on-all-main}
        (tr "workspace.branches.conflicts.all-main")]
       [:> button* {:variant "secondary" :icon i/git-branch :on-click on-all-branch}
        (tr "workspace.branches.conflicts.all-branch")]
       (when (pos? pending)
         [:span {:class (stl/css :conflicts-pending)}
          (tr "workspace.branches.conflicts.pending" (str pending))])]

      (cond
        loading?
        [:div {:class (stl/css :compare-empty)}
         [:> empty-state* {:icon i/git-merge
                           :text (tr "workspace.branches.compare.loading")}]]

        (empty? conflicts)
        [:div {:class (stl/css :compare-empty)}
         [:> empty-state* {:icon i/git-merge
                           :text (tr "workspace.branches.conflicts.empty")}]]

        :else
        [:div {:class (stl/css :compare-body)}
         [:div {:class (stl/css :conflicts-list-side)}
          [:span {:class (stl/css :conflicts-list-head)}
           (tr "workspace.branches.conflicts.list-head" (str total))]
          [:ul {:class (stl/css :compare-list)}
           (for [[idx c] (map-indexed vector conflicts)]
             [:> branch-conflict-item* {:key idx
                                        :conflict c
                                        :index idx
                                        :selected sel-idx
                                        :resolution (get resolutions (:id c))
                                        :on-select on-select}])]]

         [:div {:class (stl/css :compare-detail)}
          (when sel
            (let [sel-attrs (:changed-attrs sel)
                  attr-keys (keys sel-attrs)
                  has-attrs? (seq sel-attrs)]
              [:*
               [:div {:class (stl/css :conflict-detail-head)}
                [:div {:class (stl/css :conflict-detail-titlerow)}
                 [:div {:class (stl/css :conflict-detail-icon)}
                  [:> i/icon* {:icon-id sel-icon}]]
                 [:h3 {:class (stl/css :conflict-detail-title)} (:label sel)]
                 [:div {:class (stl/css :conflict-global-actions)}
                  [:> button* {:variant (if (= sel-res :main) "primary" "secondary")
                               :on-click on-use-main}
                   (tr "workspace.branches.conflicts.use-main")]
                  [:> button* {:variant (if (= sel-res :branch) "primary" "secondary")
                               :on-click on-use-branch}
                   (tr "workspace.branches.conflicts.use-branch")]]]
                [:span {:class (stl/css :conflict-detail-subtitle)}
                 (cond-> ""
                   sel-type   (str (tr sel-type))
                   sel-reason (str " - " (tr sel-reason)))]]

               [:> conflict-preview-row* {:conflict sel
                                          :resolution sel-res
                                          :base-sub base-sub
                                          :main-sub main-sub
                                          :branch-sub branch-sub}]

               (if has-attrs?
                 [:div {:class (stl/css :prop-table)}
                  [:div {:class (stl/css :prop-table-head)}
                   [:span {:class (stl/css :prop-table-th)}
                    (tr "workspace.branches.conflicts.prop-header")]
                   [:span {:class (stl/css :prop-table-th)}
                    (tr "workspace.branches.conflicts.card.main")]
                   [:span {:class (stl/css :prop-table-th)}
                    (tr "workspace.branches.conflicts.card.branch")]]
                  (for [[attr {:keys [main branch]}] sel-attrs]
                    [:> conflict-prop-row* {:key (str attr)
                                            :id (:id sel)
                                            :attr attr
                                            :main main
                                            :branch branch
                                            :choice (attr-choice sel-res attr)
                                            :attrs attr-keys}])]

                 [:p {:class (stl/css :conflict-whole-hint)}
                  (tr "workspace.branches.conflicts.whole-hint")])]))]])

      [:div {:class (stl/css :compare-footer)}
       [:div {:class (stl/css :compare-footer-info)}
        (if all-done?
          [:*
           [:> i/icon* {:icon-id i/tick :size "s"}]
           (tr "workspace.branches.conflicts.footer-ready")]
          [:*
           [:span {:class (stl/css :compare-footer-conflicts)}
            [:> i/icon* {:icon-id i/triangle-alert :size "s"}]]
           [:span {:class (stl/css :compare-footer-conflicts)}
            (tr "workspace.branches.conflicts.footer-pending" (str pending))]])]
       [:div {:class (stl/css :compare-footer-actions)}
        ;; applying a MERGE deletes the branch unless the user opts to
        ;; keep it archived — same choice the plain merge dialog offers
        (when (not= mode :update)
          [:> checkbox* {:id "conflicts-archive-branch"
                         :label (tr "workspace.branches.merge.archive-label")
                         :checked keep?
                         :on-change on-toggle-keep}])
        [:> button* {:variant "secondary" :on-click on-close} (tr "labels.cancel")]
        [:> button* {:variant "primary"
                     :icon i/git-merge
                     :disabled (not all-done?)
                     :on-click on-apply}
         (tr "workspace.branches.conflicts.apply")]]]]]))

;; --- Branch context banner (shown while editing a branch)

(mf/defc branch-context-banner*
  [{:keys [file-id]}]
  (let [ctx         (mf/deref branch-context)
        merged?     (contains? #{"merged" "archived"} (:status ctx))

        collapsed*  (mf/use-state false)
        collapsed?  (deref collapsed*)
        on-toggle   (mf/use-fn #(swap! collapsed* not))

        ;; persistence status drives a refetch of the change counts on save
        persistence (mf/deref refs/persistence)
        pstatus     (:status persistence)

        ;; with pull requests enabled the merge action moves behind the
        ;; kebab menu and requesting a review becomes the primary action
        ;; (or opening the branch's already-open pull request)
        prs-enabled? (dwpr/enabled?)
        open-pr      (when prs-enabled?
                       (->> (:data (mf/deref refs/pull-requests))
                            (filter #(and (= "open" (:status %))
                                          (= (:id ctx) (:file-branch-id %))))
                            (first)))

        menu-open*    (mf/use-state false)
        menu-open?    (deref menu-open*)
        on-open-menu  (mf/use-fn
                       (fn [event]
                         ;; the dropdown closes itself on any outside
                         ;; click: the opening one must not reach it
                         (dom/stop-propagation event)
                         (reset! menu-open* true)))
        on-close-menu (mf/use-fn #(reset! menu-open* false))

        on-compare   (mf/use-fn (mf/deps ctx) #(modal/show! :branch-compare {:branch ctx}))
        on-update    (mf/use-fn (mf/deps ctx) #(confirm-update! ctx))
        on-open-main (mf/use-fn (mf/deps ctx) #(st/emit! (dwb/open-branch (:source-file-id ctx))))
        on-resolve   (mf/use-fn (mf/deps ctx) #(modal/show! :branch-conflicts {:branch ctx :mode :merge}))

        on-merge
        (mf/use-fn
         (mf/deps ctx)
         (fn [_]
           (reset! menu-open* false)
           (confirm-merge! ctx)))

        on-request-review
        (mf/use-fn
         (mf/deps ctx)
         #(modal/show! :create-pull-request {:branch ctx}))

        on-open-review
        (mf/use-fn
         (mf/deps open-pr)
         #(st/emit! (dwpr/open-pull-request open-pr)))]

    (mf/with-effect [file-id]
      (when (contains? cf/flags :branching)
        (st/emit! (dwb/fetch-branch-context)))
      ;; the primary action depends on whether the branch already has an
      ;; open pull request
      (when (dwpr/enabled?)
        (st/emit! (dwpr/fetch-pull-requests))))

    ;; refresh the change counts every time a save settles (and when
    ;; returning to the tab, so "main advanced" is surfaced too)
    (mf/with-effect [pstatus]
      (when (and (= :saved pstatus) (contains? cf/flags :branching))
        (st/emit! (dwb/fetch-branch-context))))

    (mf/with-effect []
      (let [key (events/listen globals/window "focus"
                               (fn [_]
                                 (when (contains? cf/flags :branching)
                                   (st/emit! (dwb/fetch-branch-context)))))]
        (fn [] (events/unlistenByKey key))))

    (when ctx
      [:div {:class (stl/css-case :branch-banner true
                                  :branch-banner-merged merged?
                                  :is-collapsed collapsed?)}
       [:button {:class (stl/css :branch-banner-handle)
                 :title (tr (if collapsed?
                              "workspace.branches.banner.expand"
                              "workspace.branches.banner.collapse"))
                 :aria-label (tr (if collapsed?
                                   "workspace.branches.banner.expand"
                                   "workspace.branches.banner.collapse"))
                 :on-click on-toggle}
        [:div {:class (stl/css :branch-banner-handle-btn)}]]
       [:div {:class (stl/css :branch-banner-content)}
        [:div {:class (stl/css :branch-banner-icon)}
         [:> i/icon* {:icon-id i/git-branch}]]
        [:div {:class (stl/css :branch-banner-text)}
         [:span {:class (stl/css :branch-banner-title)}
          (tr (if merged?
                "workspace.branches.banner.merged"
                "workspace.branches.banner.on-branch"))]
         [:span {:class (stl/css :branch-banner-name)} (:name ctx)]
         [:span {:class (stl/css :branch-banner-from)} (tr "workspace.branches.banner.from-main")]]

        (if merged?
          [:> button* {:variant "secondary"
                       :icon i/arrow-up-right
                       :on-click on-open-main}
           (tr "workspace.branches.banner.view-main")]

          (let [conflicts (or (:conflicts ctx) 0)]
            [:div {:class (stl/css :branch-banner-actions)}
             [:span {:class (stl/css :branch-banner-counts)}
              [:span {:class (stl/css :count-ahead)}
               [:> i/icon* {:icon-id i/arrow-up :size "s"}] (dm/str (:ahead ctx))]
              [:span {:class (stl/css :count-behind)}
               [:> i/icon* {:icon-id i/arrow-down :size "s"}] (dm/str (:behind ctx))]]

             (when (pos? conflicts)
               [:span {:class (stl/css :item-badge :badge-conflict)}
                (tr "workspace.branches.banner.conflicts" (dm/str conflicts))])

             [:> button* {:variant "secondary"
                          :icon i/switch
                          :on-click on-compare}
              (tr "workspace.branches.compare")]

             (cond
               (pos? conflicts)
               [:> button* {:variant "primary"
                            :icon i/triangle-alert
                            :on-click on-resolve}
                (tr "workspace.branches.conflicts.resolve")]

               (pos? (:behind ctx))
               [:> button* {:variant "primary"
                            :icon i/status-update
                            :on-click on-update}
                (tr "workspace.branches.update")]

               ;; with pull requests enabled the primary flow is asking
               ;; for a review (merging moves behind the kebab menu)
               (and prs-enabled? (some? open-pr))
               [:> button* {:variant "primary"
                            :icon i/git-pull-request-arrow
                            :on-click on-open-review}
                (tr "workspace.pull-requests.actions.open-review")]

               (and prs-enabled? (pos? (:ahead ctx)))
               [:> button* {:variant "primary"
                            :icon i/git-pull-request-arrow
                            :on-click on-request-review}
                (tr "workspace.pull-requests.menu.request-review")]

               (pos? (:ahead ctx))
               [:> button* {:variant "primary"
                            :icon i/git-merge
                            :on-click on-merge}
                (tr "workspace.branches.merge.action")])

             (when prs-enabled?
               [:div {:class (stl/css :branch-banner-menu)}
                [:> icon-button* {:variant "ghost"
                                  :icon i/menu
                                  :aria-label (tr "labels.options")
                                  :on-click on-open-menu}]
                [:> dropdown-menu* {:show menu-open?
                                    :on-close on-close-menu
                                    :class (stl/css :branch-banner-options-dropdown)}
                 [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-merge}
                  (tr "workspace.pull-requests.actions.merge")]]])]))]])))
