import { expect, it } from 'vitest';
import { directoryPreviewOffset } from '../directory-preview';
it('requests the first and subsequent preview pages with non-negative offsets', () => {
 expect([1,2,3].map(directoryPreviewOffset)).toEqual([0,50,100]);
 for(const invalid of [0,-1,NaN,Infinity,1.5])expect(directoryPreviewOffset(invalid)).toBe(0);
});
