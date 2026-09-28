(ns frontend-tests.tokens.logic.tokens-status-test
  (:require
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.tokens :as tho]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.tokens.helpers.state :as tohs]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(defn- setup-file
  "Create a file with several token themes and token sets, some active and
  some inactive. Returns the file map with :tokens-lib and :tokens-status
  in its :data."
  []
  (-> (tho/sample-file-with-tokens
       :lib-fn #(-> %
                    ;; Add token sets
                    (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-active)
                                                       :name "set-active"))
                    (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-inactive)
                                                       :name "set-inactive"))
                    ;; Add themes
                    (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-active)
                                                           :name "theme-active"
                                                           :group "group-1"
                                                           :sets #{"set-active"}))
                    (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-inactive)
                                                           :name "theme-inactive"
                                                           :group "group-1"
                                                           :sets #{"set-inactive"})))
       :status-fn #(-> %
                       (ctos/set-tokens-status #{(thi/id :theme-active)}
                                               #{(thi/id :set-active)})))))

(defn- get-tokens-status [file]
  (get-in file [:data :tokens-status]))

(t/deftest test-set-token-theme-active-deactivate
  (t/async
    done
    (let [file      (setup-file)
          store     (ths/setup-store file)
          theme-id  (thi/id :theme-active)
          set-id    (thi/id :set-active)
          events    [(dwtl/set-token-theme-active theme-id false)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               status  (get-tokens-status file')]
           (t/is (not (ctos/theme-active? status theme-id))
                 "theme should be inactive after deactivation")
           (t/is (not (ctos/set-active? status set-id))
                 "set should be inactive when its theme is deactivated")))))))

(t/deftest test-set-token-theme-active-activate
  (t/async
    done
    (let [file      (setup-file)
          store     (ths/setup-store file)
          theme-id  (thi/id :theme-inactive)
          set-id    (thi/id :set-inactive)
          events    [(dwtl/set-token-theme-active theme-id true)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               status  (get-tokens-status file')]
           (t/is (ctos/theme-active? status theme-id)
                 "theme should be active after activation")
           (t/is (ctos/set-active? status set-id)
                 "set should be active when its theme is activated")))))))

(t/deftest test-toggle-token-theme-active-deactivate
  (t/async
    done
    (let [file      (setup-file)
          store     (ths/setup-store file)
          theme-id  (thi/id :theme-active)
          set-id    (thi/id :set-active)
          events    [(dwtl/toggle-token-theme-active theme-id)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               status  (get-tokens-status file')]
           (t/is (not (ctos/theme-active? status theme-id))
                 "active theme should become inactive after toggle")
           (t/is (not (ctos/set-active? status set-id))
                 "set should be inactive when its theme is toggled off")))))))

(t/deftest test-toggle-token-theme-active-activate
  (t/async
    done
    (let [file      (setup-file)
          store     (ths/setup-store file)
          theme-id  (thi/id :theme-inactive)
          set-id    (thi/id :set-inactive)
          events    [(dwtl/toggle-token-theme-active theme-id)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               status  (get-tokens-status file')]
           (t/is (ctos/theme-active? status theme-id)
                 "inactive theme should become active after toggle")
           (t/is (ctos/set-active? status set-id)
                 "set should be active when its theme is toggled on")))))))
