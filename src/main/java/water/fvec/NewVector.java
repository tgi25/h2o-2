package water.fvec;

import java.util.Arrays;
import water.*;
import water.parser.DParseTask;

// An uncompressed chunk of data, support an append operation
public class NewVector extends BigVector {
  final int _cidx;
  transient long _ls[];         // Mantissa
  transient int _xs[];          // Exponent

  NewVector( AppendableVec vec, int cidx ) { 
    _vec = vec;                 // Owning AppendableVec
    _cidx = cidx;               // This chunk#
    _ls = new long[4];          // A little room for data
    _xs = new int [4];
  }

  // Fast-path append long data
  @Override void append2( long l, int x ) {
    if( _len >= _ls.length ) append2slow();
    _ls[_len] = l;  
    _xs[_len] = x;
    _len++;
  }
  // Slow-path append data
  void append2slow( ) {
    if( _len > ValueArray.CHUNK_SZ )
      throw new ArrayIndexOutOfBoundsException(_len);
    _ls = Arrays.copyOf(_ls,_len<<1);
    _xs = Arrays.copyOf(_xs,_len<<1);
  }

  // Do any final actions on a completed NewVector.  Mostly: compress it, and
  // do a DKV put on an appropriate Key.  The original NewVector goes dead
  // (does not live on inside the K/V store).
  public void close(Futures fs) {
    DKV.put(_vec.chunkKey(_cidx),compress(),fs);
    ((AppendableVec)_vec).closeChunk(_cidx,_len);
  }

  // Study this NewVector and determine an appropriate compression scheme.
  // Return the data so compressed.
  static final int MAX_FLOAT_MANTISSA = 0x7FFFFF;
  BigVector compress() {

    // See if we can sanely normalize all the data to the same fixed-point.
    int  xmin = Integer.MAX_VALUE;   // min exponent found
    long lemin= 0, lemax=lemin; // min/max at xmin fixed-point
    boolean overflow=false;
    boolean first=true;
    for( int i=0; i<_len; i++ ) {
      long l = _ls[i];
      int  x = _xs[i];
      if( l==0 ) x=0;           // Canonicalize zero exponent
      // Remove any trailing zeros / powers-of-10
      long t;
      while( l!=0 && (t=l/10)*10==l ) { l=t; x++; }
      // Track largest/smallest values at xmin scale.  Note overflow.
      if( x < xmin ) {
        if( !first ) {
          overflow |= (lemin < Integer.MIN_VALUE) | (lemax > Integer.MAX_VALUE);
          if( xmin-x >= 10 ) overflow = true;
          else {
            lemin *= DParseTask.pow10i(xmin-x);
            lemax *= DParseTask.pow10i(xmin-x);
          }
        }
        xmin = x;               // Smaller xmin
      }
      // *this* value, at the smallest scale
      long le = l*DParseTask.pow10i(x-xmin);
      if( first || le < lemin ) lemin=le;
      if( first || le > lemax ) lemax=le;
      first = false;
    }

    water.util.Log.unwrap(System.err,"COMPRESS: "+lemin+"e"+xmin+" - "+lemax+"e"+xmin);

    // Exponent scaling: replacing numbers like 1.3 with 13e-1.  '13' fits in a
    // byte and we scale the column by 0.1.  A set of numbers like
    // {1.2,23,0.34} then is normalized to always be represented with 2 digits
    // to the right: {1.20,23.00,0.34} and we scale by 100: {120,2300,34}.
    // This set fits in a 2-byte short.

    // We use exponent-scaling for bytes & shorts only; it's uncommon (and not
    // worth it) for larger numbers.  We need to get the exponents to be
    // uniform, so we scale up the largest lmax by the largest scale we need
    // and if that fits in a byte/short - then it's worth compressing.  Other
    // wise we just flip to a float or double representation.
    if( xmin != 0 ) {
      if( !overflow && lemax-lemin < 255 ) // Fits in scaled biased byte?
        return new C1SVector(bufX(lemin,xmin,C1SVector.OFF,0),(int)lemin,DParseTask.pow10(xmin));
      if( !overflow && lemax-lemin < 65535 )
        return new C2SVector(bufX(lemin,xmin,C2SVector.OFF,1),(int)lemin,DParseTask.pow10(xmin));
      if( Math.abs(lemax-lemin) <= MAX_FLOAT_MANTISSA && -35 <= xmin && xmin <= 35 )
        return new C4FVector(bufF(2));
      return new C8DVector(bufF(3));
    }

    // Compress column into a byte
    if( lemax-lemin < 255 ) {         // Span fits in a byte?
      if( 0 <= lemin && lemax < 255 ) // Span fits in an unbiased byte?
        return new C1Vector(bufX(0,0,C1Vector.OFF,0));
      return new C1SVector(bufX(lemin,0,C1SVector.OFF,0),(int)lemin,1);
    } 

    // Compress column into a short
    if( lemax-lemin < 65535 ) {               // Span fits in a biased short?
      if( -32767 <= lemin && lemax <= 32767 ) // Span fits in an unbiased short?
        return new C2Vector(bufX(0,0,C2Vector.OFF,1));
      return new C2SVector(bufX(lemin,0,C2SVector.OFF,1),(int)lemin,1);
    } 

    // Compress column into ints
    if( Integer.MIN_VALUE < lemin && lemax <= Integer.MAX_VALUE )
      return new C4Vector(bufX(0,0,0,2));

    return new C8Vector(bufX(0,0,0,3));
  }

  // Compute a compressed integer buffer
  private byte[] bufX( long bias, int scale, int off, int log ) {
    byte[] bs = new byte[(_len<<log)+off];
    for( int i=0; i<_len; i++ ) {
      int x = _xs[i]-scale;
      long le = x >= 0 
        ? _ls[i]*DParseTask.pow10i( x)
        : _ls[i]/DParseTask.pow10i(-x);
      le -= bias;
      switch( log ) {
      case 0:          bs [i    +off] = (byte)le ; break;
      case 1: UDP.set2(bs,(i<<1)+off,  (short)le); break;
      case 2: UDP.set4(bs,(i<<2)+off,    (int)le); break;
      case 3: UDP.set8(bs,(i<<3)+off,         le); break;
      default: H2O.fail();
      }
    }
    return bs;
  }

  // Compute a compressed float buffer
  private byte[] bufF( int log ) {
    byte[] bs = new byte[_len<<log];
    for( int i=0; i<_len; i++ ) {
      double le = _ls[i]*DParseTask.pow10(_xs[i]);
      switch( log ) {
      case 2: UDP.set4f(bs,(i<<2), (float)le); break;
      case 3: UDP.set8d(bs,(i<<3),        le); break;
      default: H2O.fail();
      }
    }
    return bs;
  }
  
  @Override long   at_impl ( int i ) { throw H2O.fail(); }
  @Override double atd_impl( int i ) { throw H2O.fail(); }
  @Override public AutoBuffer write(AutoBuffer bb) { throw H2O.fail(); }
  @Override public NewVector read(AutoBuffer bb) { throw H2O.fail(); }
}
