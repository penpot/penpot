(ns uxbox.data.projects
  (:require [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.time :as time]
            [bouncer.validators :as v]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static +project-schema+
  {:name [v/required v/string]
   :width [v/required v/integer]
   :height [v/required v/integer]
   :layout [v/required sc/keyword]})

(def ^:static +page-schema+
  {:name [v/required v/string]
   :width [v/required v/integer]
   :height [v/required v/integer]
   :project [v/required sc/uuid]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn assoc-project
  "A reduce function for assoc the project
  to the state map."
  [state proj]
  (let [uuid (:id proj)]
    (update-in state [:projects-by-id] assoc uuid proj)))

(defn assoc-page
  "A reduce function for assoc the page
  to the state map."
  [state page]
  (let [uuid (:id page)]
    (update-in state [:pages-by-id] assoc uuid page)))

(defn project-pages
  "Get a ordered list of pages that
  belongs to a specified project."
  [state projectid]
  (->> (vals (:pages-by-id state))
       (filter #(= projectid (:project %)))
       (sort-by :created)
       (into [])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-page
  [{:keys [name width height project] :as data}]
  (sc/validate! +page-schema+ data)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [page {:id (random-uuid)
                  :project project
                  :created (time/now :unix)
                  :name name
                  :width width
                  :height height}]
        (assoc-page state page)))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/create-page>"))))

(defn update-page-name
  [pageid name]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:pages-by-id pageid] assoc :name name))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/update-page-name>"))))

(defn delete-page
  [pageid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:pages-by-id] dissoc pageid))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/edit-page>"))))

(defn create-project
  [{:keys [name width height layout] :as data}]
  (sc/validate! +project-schema+ data)
  (let [uuid (random-uuid)]
    (reify
      rs/UpdateEvent
      (-apply-update [_ state]
        (let [proj {:id uuid
                    :name name
                    :width width
                    :created (time/now :unix)
                    :height height
                    :layout layout}]
          (assoc-project state proj)))

      rs/EffectEvent
      (-apply-effect [_ state]
        (rs/emit! (create-page {:name "Page 1"
                                :width width
                                :height height
                                :project uuid})))

      IPrintWithWriter
      (-pr-writer [mv writer _]
        (-write writer "#<event:u.s.p/create-project>")))))

(defn initialize-workspace
  [projectid pageid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [s {:project projectid
               :toolboxes #{}
               :page pageid}]
        (update state :workspace merge s)))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/initialize-workspace>"))))

(defn set-dashboard-section
  [section]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:dashboard :section] section))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/go-to-project"))))

(defn go-to-project
  "A shortcut event that redirects the user to the
  first page of the project."
  ([projectid]
   (go-to-project projectid nil))
  ([projectid pageid]
   (reify
     rs/EffectEvent
     (-apply-effect [_ state]
       (if pageid
         (rs/emit! (r/navigate :workspace/page {:project-uuid projectid
                                                :page-uuid pageid}))
         (let [pages (project-pages state projectid)
               pageid (:id (first pages))]
           (rs/emit! (r/navigate :workspace/page {:project-uuid projectid
                                             :page-uuid pageid})))))
     IPrintWithWriter
     (-pr-writer [mv writer _]
       (-write writer "#<event:u.s.p/go-to-project")))))
