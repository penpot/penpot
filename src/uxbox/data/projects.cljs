(ns uxbox.data.projects
  (:require [bouncer.validators :as v]
            [cuerdas.core :as str]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.time :as time]
            [uxbox.state.project :as stpr]
            [uxbox.util.data :refer (without-keys)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME use only one ns for validators

(def ^:static +project-schema+
  {:name [v/required v/string]
   :width [v/required v/integer]
   :height [v/required v/integer]
   :layout [sc/keyword]})

(def ^:static +create-page-schema+
  {:name [v/required v/string]
   :layout [sc/keyword]
   :width [v/required v/integer]
   :height [v/required v/integer]
   :project [v/required sc/uuid]})

(def ^:static +update-page-schema+
  {:name [v/required v/string]
   :width [v/required v/integer]
   :height [v/required v/integer]
   :layout [sc/keyword]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sort-projects-by
  [ordering projs]
  (case ordering
    :name (sort-by :name projs)
    :created (reverse (sort-by :created projs))
    projs))

(defn contains-term?
  [phrase term]
  (str/contains? (str/lower phrase) (str/trim (str/lower term))))

(defn filter-projects-by
  [term projs]
  (if (str/blank? term)
    projs
    (filter #(contains-term? (:name %) term) projs)))

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
                  :shapes []
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
      (let [shapeids (get-in state [:pages-by-id pageid :shapes])]
        (as-> state $
          (update $ :shapes-by-id without-keys shapeids)
          (update $ :pages-by-id dissoc pageid))))

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
                    :height height}]
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

(defn delete-project
  [proj]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (dissoc-project state proj))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/delete-project>"))))

(defn go-to
  "A shortcut event that redirects the user to the
  first page of the project."
  ([projectid]
   (go-to projectid nil))
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
       (-write writer "#<event:u.s.p/go-to")))))
