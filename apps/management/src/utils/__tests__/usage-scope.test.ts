import {expect,it} from 'vitest';
import {sameUsageScope} from '../usage-scope';

it('rejects pages from changed or unknown scopes while allowing reordered zone sets',()=>{
 const previous={from:1,until:2,zones:['sandbox','office']};
 expect(sameUsageScope(previous,{...previous,zones:['office','sandbox']})).toBe(true);
 expect(sameUsageScope(previous,{...previous,zones:['sandbox']})).toBe(false);
 expect(sameUsageScope(previous,{...previous,zones:['sandbox','private']})).toBe(false);
 expect(sameUsageScope(previous,{...previous,zones:undefined})).toBe(false);
 expect(sameUsageScope(previous,{...previous,until:3})).toBe(false);
});
