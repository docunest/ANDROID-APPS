import type { ComponentType } from 'react';
import { Redirect } from 'expo-router';
import { useSession } from '@shared/session';
import { Loading } from './ui';

/** Screens reachable by deep link / reload need the saved server before they render. */
export function requireAccount<P extends object>(Screen: ComponentType<P>) {
  return function Guarded(props: P) {
    const { ready, account } = useSession();
    if (!ready) return <Loading />;
    if (!account) return <Redirect href="/setup" />;
    return <Screen {...props} />;
  };
}
