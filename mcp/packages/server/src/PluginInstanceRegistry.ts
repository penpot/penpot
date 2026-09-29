/** Describes a connected Penpot plugin instance. */
export interface PluginInstance {
    readonly pluginInstanceId: string;
    readonly userToken: string | null;
}

/** Indexes active plugin instances by authenticated user and browser tab. */
export class PluginInstanceRegistry<T extends PluginInstance> {
    private readonly instancesByUser = new Map<string, Map<string, T>>();

    /** Registers an instance, replacing an older socket for the same user and tab. */
    public register(instance: T): T | undefined {
        if (!instance.userToken) {
            return undefined;
        }

        let instances = this.instancesByUser.get(instance.userToken);
        if (!instances) {
            instances = new Map<string, T>();
            this.instancesByUser.set(instance.userToken, instances);
        }

        const replaced = instances.get(instance.pluginInstanceId);
        instances.set(instance.pluginInstanceId, instance);
        return replaced;
    }

    /** Removes an instance only when it still owns its registration. */
    public unregister(instance: T): boolean {
        if (!instance.userToken) {
            return false;
        }

        const instances = this.instancesByUser.get(instance.userToken);
        if (instances?.get(instance.pluginInstanceId) !== instance) {
            return false;
        }

        instances.delete(instance.pluginInstanceId);
        if (instances.size === 0) {
            this.instancesByUser.delete(instance.userToken);
        }
        return true;
    }

    /** Lists a user's connected instances without exposing instances owned by other users. */
    public list(userToken: string): T[] {
        return Array.from(this.instancesByUser.get(userToken)?.values() ?? []);
    }

    /** Resolves an explicit instance or the sole instance owned by a user. */
    public resolve(userToken: string, pluginInstanceId?: string): T {
        const instances = this.instancesByUser.get(userToken);
        if (pluginInstanceId) {
            const instance = instances?.get(pluginInstanceId);
            if (!instance) {
                throw new Error(`Penpot plugin instance '${pluginInstanceId}' is not connected for this user.`);
            }
            return instance;
        }

        if (!instances || instances.size === 0) {
            throw new Error(`No Penpot plugin instances are connected for this user.`);
        }
        if (instances.size > 1) {
            throw new Error(`Multiple Penpot tabs are connected. Pass pluginInstanceId to select one.`);
        }
        return instances.values().next().value as T;
    }
}
