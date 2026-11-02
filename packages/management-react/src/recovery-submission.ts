type Submission={id:string;expectedVersion:number;requestKey:string;kind:string;content:string;refused?:boolean};
type Draft=Omit<Submission,'requestKey'|'refused'>;
type Pending=Draft & {requestKey?:string;refused?:boolean};

/** Unknown outcomes replay the original key/version; explicit rejection permits a fresh request. */
export function prepareRecoverySubmission(previous:Pending|null,draft:Draft,newKey:()=>string):Submission{
 if(previous&&typeof previous.requestKey==='string'&&previous.requestKey&&!previous.refused&&previous.id===draft.id&&previous.kind===draft.kind&&previous.content===draft.content)return previous as Submission;
 return {...draft,requestKey:newKey()};
}
