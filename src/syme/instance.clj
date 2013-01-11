(ns syme.instance
  (:require [pallet.core :as pallet]
            [pallet.api :as api]
            [pallet.actions :as actions]
            [pallet.action :as action]
            [pallet.compute :as compute]
            [pallet.node :as node]
            [pallet.crate :as crate]
            [pallet.core.session :as session]
            [pallet.crate.automated-admin-user :as admin]
            [pallet.phase :as phase]
            [clj-http.client :as http]
            [environ.core :refer [env]]
            [tentacles.repos :as repos]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [syme.db :as db]))

(def pubkey (str (io/file (System/getProperty "user.dir")
                          "data" "keys" "syme.pub")))

(def privkey (str (io/file (System/getProperty "user.dir")
                           "data" "keys" "syme")))

(def admin-user (api/make-user "syme"
                               :public-key-path pubkey
                               :private-key-path privkey))

(def write-key-pair
  (delay
   (.mkdirs (io/file "data" "keys"))
   (io/copy (.getBytes (.replaceAll (env :private-key) "\\\\n" "\n"))
            (io/file privkey))
   (io/copy (.getBytes (env :public-key))
            (io/file pubkey))))

(defn get-keys [username]
  (let [keys (-> (http/get (format "https://github.com/%s.keys" username))
                 (:body) (.split "\n"))]
    (map (memfn getBytes) keys)))

(defn bootstrap-phase [username project repo]
  (println "Bootstrapping...")
  (let [ip (node/primary-ip (crate/target-node))
        desc (:description @repo)]
    (sql/with-connection db/db
      (db/create username project desc ip)))
  (println "Creating admin user...")
  (admin/automated-admin-user
   "syme" (.getBytes (:public-key env)))
  (println "Done bootstrapping."))

(defn configure-phase [username project invite]
  (println "Configuring...")
  (db/status username project "configuring")
  (admin/automated-admin-user "syme" (.getBytes (:public-key env)))
  (println "Adding owner" username)
  (apply admin/automated-admin-user username (get-keys username))
  (sql/with-connection db/db
    (doseq [invitee (.split invite ",? +")]
      (println "Adding invitee" invitee)
      (db/invite username project invitee)
      (apply admin/automated-admin-user invitee (get-keys invitee))))
  (db/status username project "checking out")
  (actions/package "git")
  (actions/package "tmux")
  (println "Cloning repo...")
  ;; TODO: this doesn't work
  (action/with-action-options {:sudo-user username :script-prefix :no-prefix}
    (actions/exec-checked-script
     "Project clone"
     ~(format "sudo -iu %s git clone git://github.com/%s/%s.git"
              username username project)))
  ;; TODO: set up tmux config and shared wrapper
  (db/status username project "ready")
  (println "Done!"))

;; TODO: log instance state in DB
;; * bootstrapped
;; * configured
;; * ready
;; * halted

(defn launch [username {:keys [project invite identity credential]}]
  (force write-key-pair)
  (alter-var-root #'pallet.core.user/*admin-user* (constantly admin-user))
  (let [group (str username "/" project)
        repo (future (apply repos/specific-repo (.split project "/")))]
    (println "Converging" group "...")
    (pallet/converge
     (pallet/group-spec
      group, :count 1
      :node-spec (pallet/node-spec :image {:os-family :ubuntu
                                           :image-id "us-east-1/ami-3c994355"})
      :phases {:bootstrap (partial bootstrap-phase username project repo)
               :configure (partial configure-phase username project invite)})
     :compute (compute/compute-service "aws-ec2"
                                       :identity identity
                                       :credential credential))))
